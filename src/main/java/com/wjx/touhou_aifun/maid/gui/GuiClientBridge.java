package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.*;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owner client is a renderer/input translator, never an authoritative menu executor. */
public final class GuiClientBridge {
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> PREVIEW_TICKS = new HashMap<>();
    private static final class Pending {
        final MaidGuiSession session;
        final long deadline;
        final CompletableFuture<JsonObject> future = new CompletableFuture<>();
        final boolean input;
        byte[][] chunks;
        String json;
        int bytes;
        Pending(MaidGuiSession session, boolean input) {
            this.session = session; this.input = input; deadline = session.maid.level().getGameTime() + 240;
            long started=System.nanoTime();
            var timing=com.wjx.touhou_aifun.chat.agent.AgentTelemetry.start(session.callback,input?"gui_client_input":"gui_client_capture");
            future.whenComplete((result,error)->timing.finish(error!=null?com.wjx.touhou_aifun.chat.agent.AgentTelemetry.failureStatus(error)
                    :result!=null && result.has("error")?"error":"ok",0));
            future.whenComplete((result,error)->com.wjx.touhou_aifun.chat.agent.AgentTelemetry.stage(input?"gui_client_input":"gui_client_capture",started,0));
        }
    }
    private GuiClientBridge() { }
    public static boolean busy(MaidGuiSession session) { return PENDING.values().stream().anyMatch(p -> p.session == session); }
    public static CompletableFuture<JsonObject> request(MaidGuiSession session, String operation, JsonObject args) {
        if (!(session.maid.getOwner() instanceof ServerPlayer owner) || owner.level() != session.maid.level()) {
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("owner_client_unavailable"));
        }
        if (busy(session)) return CompletableFuture.completedFuture(MaidGuiSessionManager.error("operation_in_progress"));
        if (operation.equals("input") && (session.frame == 0 || !args.has("frame_id")
                || args.get("frame_id").getAsLong() != session.frame || !session.clientLayout.equals(MaidGuiSessionManager.string(args, "layout", "")))) {
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("stale_frame_reinspect"));
        }
        UUID id = UUID.randomUUID(); Pending pending = new Pending(session, operation.equals("input")); PENDING.put(id, pending);
        JsonObject clientArgs = args.deepCopy();
        if (session.targetPosition != null) {
            JsonArray position = new JsonArray(); position.add(session.targetPosition.getX()); position.add(session.targetPosition.getY()); position.add(session.targetPosition.getZ());
            clientArgs.add("target_position", position);
        }
        try { AIFunNetwork.sendGui(owner, new AIFunGuiRequestMessage(id, session.id, session.maid.getUUID(), operation, clientArgs.toString(), session.mirror())); }
        catch (RuntimeException e) { PENDING.remove(id); pending.future.complete(MaidGuiSessionManager.error("gui_mirror_failed")); }
        return pending.future;
    }
    public static void accept(ServerPlayer sender, AIFunGuiResultMessage message) {
        Pending pending = PENDING.get(message.request());
        if (pending == null || sender == null || !pending.session.id.equals(message.session())
                || !pending.session.maid.getUUID().equals(message.maid()) || !Objects.equals(pending.session.owner, sender.getUUID())
                || !Objects.equals(pending.session.maid.getOwnerUUID(), sender.getUUID()) || !pending.session.maid.isAlive()
                || pending.session.maid.level() != sender.level() || MaidGuiSessionManager.session(message.maid()) != pending.session) return;
        if (message.parts() < 1 || message.parts() > 60 || message.index() < 0 || message.index() >= message.parts()
                || message.data().length > 16 * 1024) { fail(message.request(), "invalid_gui_reply"); return; }
        if (pending.chunks == null) { pending.chunks = new byte[message.parts()][]; pending.json = message.json(); }
        if (pending.chunks.length != message.parts() || !pending.json.equals(message.json())) { fail(message.request(), "inconsistent_gui_reply"); return; }
        if (pending.chunks[message.index()] != null) return;
        pending.bytes += message.data().length;
        if (pending.bytes > 900 * 1024) { fail(message.request(), "gui_image_too_large"); return; }
        pending.chunks[message.index()] = message.data().clone();
        for (byte[] chunk : pending.chunks) if (chunk == null) return;
        PENDING.remove(message.request());
        try {
            JsonObject reply = JsonParser.parseString(pending.json).getAsJsonObject();
            if (reply.has("error")) { pending.future.complete(reply); return; }
            MaidGuiSession session = pending.session;
            if (pending.input) {
                JsonArray actions = reply.getAsJsonArray("actions");
                if (actions == null || actions.size() > 16) throw new IllegalArgumentException("invalid_gui_action_batch");
                // Check unsupported protocols before applying any earlier action in this batch.
                for (JsonElement value : actions) {
                    JsonObject action = value.getAsJsonObject();
                    String kind = MaidGuiSessionManager.string(action, "action", "");
                    if (kind.equals("click_slot") || kind.equals("quick_move")) {
                        int slot = MaidGuiSessionManager.number(action, "slot", -1, 0, session.menu().slots.size() - 1);
                        MaidGuiSessionManager.checkObserved(session, slot);
                    }
                    if (MaidGuiSessionManager.string(action, "action", "").equals("custom_packet")) {
                        ResourceLocation channel = new ResourceLocation(action.get("channel").getAsString());
                        if (MaidGuiAdapters.gui().stream().noneMatch(adapter -> adapter.supports(session.menu()) && adapter.supportsChannel(channel)))
                            throw new IllegalArgumentException("custom_protocol_requires_adapter:" + channel);
                    }
                }
                MaidGuiSessionManager.reserveActions(session, Math.max(0, actions.size() - 1));
                for (JsonElement value : actions) {
                    JsonObject action = value.getAsJsonObject();
                    if (MaidGuiSessionManager.string(action, "action", "").equals("custom_packet")) {
                        ResourceLocation channel = new ResourceLocation(action.get("channel").getAsString());
                        byte[] data = Base64.getDecoder().decode(action.get("payload").getAsString());
                        if (data.length > 4096) throw new IllegalArgumentException("custom_packet_too_large");
                        boolean handled = false;
                        for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(session.menu()) && adapter.supportsChannel(channel)) {
                            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                            try { if (adapter.customPacket(session, channel, buffer)) { handled = true; break; } } finally { buffer.release(); }
                        }
                        if (!handled) throw new IllegalArgumentException("custom_protocol_requires_adapter:" + channel);
                    } else {
                        String kind = MaidGuiSessionManager.string(action, "action", "");
                        if (!Set.of("click_slot", "quick_move", "button", "rename", "trade", "close_menu").contains(kind)) throw new IllegalArgumentException("unsupported_client_action");
                        JsonObject outcome = MaidGuiSessionManager.action(session, action);
                        if (outcome.has("error")) { pending.future.complete(outcome); return; }
                        if (kind.equals("close_menu")) { pending.future.complete(outcome); return; }
                    }
                }
                session.frame = 0;
                JsonObject result = session.snapshot("ok");
                if (actions.isEmpty()) result.addProperty("effect", "client_ui_only");
                pending.future.complete(result);
            } else {
                session.frame = reply.get("frame_id").getAsLong(); session.clientLayout = reply.get("layout").getAsString();
                session.clientControls = reply.getAsJsonArray("controls");
                JsonObject result = session.snapshot("ok");
                result.addProperty("gui_width", reply.get("width").getAsInt()); result.addProperty("gui_height", reply.get("height").getAsInt());
                ByteArrayOutputStream image = new ByteArrayOutputStream(); for (byte[] chunk : pending.chunks) image.writeBytes(chunk);
                if (image.size() > 0) result.addProperty("_gui_image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image.toByteArray()));
                pending.future.complete(result);
            }
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? "invalid_gui_reply" : e.getMessage();
            JsonObject failure;
            try { failure = pending.session.snapshot("failed", false); failure.addProperty("error", reason); }
            catch (RuntimeException unavailable) { failure = MaidGuiSessionManager.error(reason); }
            pending.future.complete(failure);
        }
    }
    private static void fail(UUID id, String reason) { Pending p = PENDING.remove(id); if (p != null) p.future.complete(MaidGuiSessionManager.error(reason)); }
    public static void cancel(MaidGuiSession session) {
        for (var entry : List.copyOf(PENDING.entrySet())) if (entry.getValue().session == session) fail(entry.getKey(), "cancelled");
        if (session.maid.getOwner() instanceof ServerPlayer owner) AIFunNetwork.sendGui(owner,
                new AIFunGuiRequestMessage(UUID.randomUUID(), session.id, session.maid.getUUID(), "close", "{}", new byte[0]));
    }
    public static void tick() {
        for (var entry : List.copyOf(PENDING.entrySet())) if (entry.getValue().session.maid.level().getGameTime() >= entry.getValue().deadline) fail(entry.getKey(), "gui_client_timeout");
    }
    public static void preview(ServerPlayer sender, UUID maidId, boolean stop) { preview(sender,maidId,stop ? "stop" : "status"); }
    public static void preview(ServerPlayer sender, UUID maidId, String action) {
        if (sender == null) return;
        if (!java.util.Set.of("status","pause","resume","stop").contains(action)) return;
        boolean stop = action.equals("stop");
        var entity = sender.serverLevel().getEntity(maidId);
        if (entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid
                && sender.getUUID().equals(maid.getOwnerUUID())) {
            var state = com.wjx.touhou_aifun.chat.agent.AgentRuntime.previewControl(maid,action);
            AIFunNetwork.sendTaskState(sender,maidId,state.toString());
        } else return;
        MaidGuiSession session = MaidGuiSessionManager.session(maidId);
        if (session == null || !Objects.equals(session.owner, sender.getUUID()) || sender.level() != session.maid.level()) return;
        if (stop) { MaidGuiSessionManager.cancel(maidId, "stopped_by_owner"); return; }
        long now = sender.level().getGameTime();
        if (now - PREVIEW_TICKS.getOrDefault(sender.getUUID(), -100L) < 20) return;
        PREVIEW_TICKS.put(sender.getUUID(), now);
        // Preview needs only task text. Full slot snapshots can exceed the request's JSON
        // boundary for large chests; inventory travels in the bounded binary menu mirror.
        JsonObject state = new JsonObject(); state.addProperty("wait_policy", session.policy.name());
        state.addProperty("last_action", session.lastAction); state.add("process", new Gson().toJsonTree(session.process));
        if (session.targetPosition != null) {
            JsonArray position = new JsonArray(); position.add(session.targetPosition.getX()); position.add(session.targetPosition.getY()); position.add(session.targetPosition.getZ());
            state.add("target_position", position);
        }
        try { AIFunNetwork.sendGui(sender, new AIFunGuiRequestMessage(UUID.randomUUID(), session.id, maidId,
                "preview", state.toString(), session.mirror())); } catch (RuntimeException ignored) { }
    }
}
