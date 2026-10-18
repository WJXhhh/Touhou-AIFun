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
        boolean preview;
        final net.minecraft.world.inventory.AbstractContainerMenu menu;
        byte[][] chunks;
        String json;
        int bytes;
        JsonArray actions;
        int nextAction;
        boolean applied;
        long nextTick;
        Runnable settlement;
        Pending(MaidGuiSession session, boolean input) {
            this.session = session; this.input = input; deadline = session.maid.level().getGameTime() + 240;
            this.menu = session.menu();
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
        for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(session.menu()) && adapter.semanticOnly()) {
            JsonObject result = operation.equals("input") ? MaidGuiSessionManager.error("use_projecte_semantic_actions") : session.snapshot("ok");
            result.addProperty("visual_unavailable_reason", "projecte_owner_account_uses_semantic_adapter");
            return CompletableFuture.completedFuture(result);
        }
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
        try {
            JsonObject reply = JsonParser.parseString(pending.json).getAsJsonObject();
            if (reply.has("error")) { PENDING.remove(message.request()); pending.future.complete(reply); return; }
            MaidGuiSession session = pending.session;
            if (session.menu() != pending.menu) throw new IllegalArgumentException("gui_menu_changed_reinspect");
            if (reply.has("protocol_ack")) session.actor.protocol.acknowledge(reply.get("protocol_ack").getAsLong());
            if (pending.preview) { PENDING.remove(message.request()); pending.future.complete(reply); return; }
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
                        if (MaidGuiAdapters.gui().stream().noneMatch(adapter -> adapter.supports(session.menu()) && adapter.supportsChannel(channel))
                                && !session.actor.protocol.permits(channel))
                            throw new IllegalArgumentException("custom_protocol_requires_adapter:" + channel);
                    }
                }
                MaidGuiSessionManager.reserveActions(session, Math.max(0, actions.size() - 1));
                pending.actions = actions; pending.nextTick = session.maid.level().getGameTime();
                advance(message.request(), pending);
                return;

            } else {
                session.frame = reply.get("frame_id").getAsLong(); session.clientLayout = reply.get("layout").getAsString();
                session.visuallyObservedMenu = session.menu();
                session.clientControls = reply.getAsJsonArray("controls");
                if (reply.has("active_slots")) {
                    byte[] active = Base64.getDecoder().decode(reply.get("active_slots").getAsString());
                    if (active.length > 64) throw new IllegalArgumentException("invalid_gui_slot_visibility");
                    session.clientActiveSlots = BitSet.valueOf(active);
                }
                session.clientControlsTruncated = reply.has("controls_truncated") && reply.get("controls_truncated").getAsBoolean();
                JsonObject result = session.snapshot("ok");
                result.addProperty("gui_width", reply.get("width").getAsInt()); result.addProperty("gui_height", reply.get("height").getAsInt());
                ByteArrayOutputStream image = new ByteArrayOutputStream(); for (byte[] chunk : pending.chunks) image.writeBytes(chunk);
                if (image.size() > 0) result.addProperty("_gui_image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image.toByteArray()));
                PENDING.remove(message.request());
                pending.future.complete(result);
            }
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? "invalid_gui_reply" : e.getMessage();
            JsonObject failure;
            try { failure = pending.session.snapshot("failed", false); failure.addProperty("error", reason); }
            catch (RuntimeException unavailable) { failure = MaidGuiSessionManager.error(reason); }
            PENDING.remove(message.request()); pending.future.complete(failure);
        }
    }
    private static void advance(UUID id, Pending pending) {
        MaidGuiSession session = pending.session;
        try {
            if (session.menu() != pending.menu) throw new IllegalArgumentException("gui_menu_changed_reinspect");
            if (MaidGuiSessionManager.session(session.maid.getUUID()) != session || !Objects.equals(session.owner, session.maid.getOwnerUUID())
                    || com.wjx.touhou_aifun.chat.ChatFlowManager.isSuperseded(session.maid.getUUID(), session.callback)
                    || !session.maid.isAlive() || !session.menu().stillValid(session.actor)) throw new IllegalArgumentException("gui_protocol_session_unavailable");
            if (pending.settlement != null) { settle(pending); session.snapshot("ok"); }
            if (session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) {
                if (task.stopped()) throw new IllegalArgumentException("task_cancelled");
                if (!task.task.pendingAmendment.isBlank()) throw new IllegalArgumentException("goal_updated_before_atomic_action");
            }
            if (session.unfamiliarMenu() && session.visuallyObservedMenu != session.menu())
                throw new IllegalArgumentException("unfamiliar_machine_inspect_gui_visual_first");
            while (pending.nextAction < pending.actions.size()) {
                JsonObject action = pending.actions.get(pending.nextAction++).getAsJsonObject();
                String kind = MaidGuiSessionManager.string(action, "action", "");
                if (kind.equals("custom_packet")) {
                    ResourceLocation channel = new ResourceLocation(action.get("channel").getAsString());
                    byte[] bytes = Base64.getDecoder().decode(action.get("payload").getAsString());
                    if (bytes.length > 4096) throw new IllegalArgumentException("custom_packet_too_large");
                    pending.settlement = MaidGuiSessionManager.protocolSettlement(session);
                    boolean previouslyApplied = pending.applied; pending.applied = true;
                    boolean handled = false;
                    for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(session.menu()) && adapter.supportsChannel(channel)) {
                        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
                        try { handled = adapter.customPacket(session, channel, buffer); } finally { buffer.release(); }
                        if (handled) break;
                    }
                    if (!handled) handled = session.actor.protocol.dispatch(session, channel, bytes);
                    if (!handled) { pending.applied = previouslyApplied; throw new IllegalArgumentException("custom_protocol_requires_adapter:" + channel); }
                    session.frame = 0; session.lastAction = "native_gui_protocol";
                    pending.nextTick = session.maid.level().getGameTime() + 1; return;
                }
                if (!Set.of("click_slot", "quick_move", "button", "rename", "trade", "close_menu").contains(kind))
                    throw new IllegalArgumentException("unsupported_client_action");
                // Closing cancels other pending work; this acknowledged close must retain its own result.
                if (kind.equals("close_menu")) PENDING.remove(id);
                JsonObject outcome = MaidGuiSessionManager.action(session, action);
                if (outcome.has("error") || kind.equals("close_menu")) { PENDING.remove(id); pending.future.complete(outcome); return; }
                pending.applied = true;
            }
            session.frame = 0; JsonObject result = session.snapshot("ok");
            if (pending.actions.isEmpty()) result.addProperty("effect", "client_ui_only");
            result.addProperty("result_verification", "authoritative_server_slots_and_maid_inventory");
            PENDING.remove(id); pending.future.complete(result);
        } catch (RuntimeException failure) {
            try { settle(pending); } catch (RuntimeException settlementFailure) { failure.addSuppressed(settlementFailure); }
            JsonObject result;
            String status = pending.applied ? "partial" : "failed";
            try { result = session.snapshot(status); } catch (RuntimeException unavailable) { result = new JsonObject(); result.addProperty("status", status); }
            result.addProperty("error", failure.getMessage() == null ? "gui_protocol_failed" : failure.getMessage());
            PENDING.remove(id); pending.future.complete(result);
        }
    }
    private static void fail(UUID id, String reason) {
        Pending p = PENDING.remove(id);
        if (p == null) return;
        try { settle(p); } catch (RuntimeException failure) { reason = "gui_protocol_settlement_failed:" + reason; }
        p.future.complete(MaidGuiSessionManager.error(reason));
    }
    static void settle(MaidGuiSession session) {
        for (Pending pending : List.copyOf(PENDING.values())) if (pending.session == session) settle(pending);
    }
    private static void settle(Pending pending) {
        Runnable settlement = pending.settlement; pending.settlement = null;
        if (settlement != null) settlement.run();
    }
    public static void cancel(MaidGuiSession session) {
        for (var entry : List.copyOf(PENDING.entrySet())) if (entry.getValue().session == session) fail(entry.getKey(), "cancelled");
        if (session.maid.getOwner() instanceof ServerPlayer owner) AIFunNetwork.sendGui(owner,
                new AIFunGuiRequestMessage(UUID.randomUUID(), session.id, session.maid.getUUID(), "close", "{}", new byte[0]));
    }
    public static void tick() {
        for (var entry : List.copyOf(PENDING.entrySet())) {
            Pending pending = entry.getValue();
            if (pending.actions != null && pending.session.maid.level().getGameTime() >= pending.nextTick) advance(entry.getKey(), pending);
        }
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
        if (busy(session)) return;
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
        UUID id = UUID.randomUUID(); Pending pending = new Pending(session, false); pending.preview = true; PENDING.put(id, pending);
        try { AIFunNetwork.sendGui(sender, new AIFunGuiRequestMessage(id, session.id, maidId,
                "preview", state.toString(), session.mirror())); } catch (RuntimeException ignored) { fail(id, "gui_preview_failed"); }
    }
}
