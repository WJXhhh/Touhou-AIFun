package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.maid.gui.*;
import com.wjx.touhou_aifun.client.gui.automation.GuiOperationPreviewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Automated integrated-client check; isolated world only, no API keys or LLM calls. */
@Mod.EventBusSubscriber(modid = "aifun_gui_test", value = Dist.CLIENT)
public final class GuiClientChecks {
    private static int ticks;
    private static boolean loaded, started, finished;
    private static volatile boolean clientTracked;
    private static LLMCallback callback;
    private static EntityMaid maid;
    private static UUID owner;
    private static Screen sentinel;
    private static Screen expectedScreen;
    private static long startTick;
    private static JsonObject frame;
    private static int sequence;
    private record Delayed(long at, Runnable task) { }
    private static final List<Delayed> delayed = new ArrayList<>();
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("aifun.guiQa.autorun") || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance(); ticks++;
        if (!loaded && ticks > 100 && mc.screen != null) {
            loaded = true; mc.createWorldOpenFlows().loadLevel(mc.screen, "AIFun-GUI-QA");
        }
        if (loaded && !started && mc.player != null && mc.getSingleplayerServer() != null && ticks > 180) {
            if (mc.player.isDeadOrDying()) {
                if (ticks % 20 == 0) mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                        net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                return;
            }
            started = true; owner = mc.player.getUUID();
            sentinel = new Screen(Component.literal("Owner context sentinel")) {
                @Override public boolean isPauseScreen() { return false; }
                @Override public void render(GuiGraphics graphics, int x, int y, float partial) {
                    graphics.fill(0, 0, width, height, 0xff183322);
                    graphics.drawString(font, "AIFun isolated GUI checks running", 16, 16, 0xffffff);
                }
            };
            expectedScreen = sentinel; mc.setScreen(sentinel); mc.options.pauseOnLostFocus = false;
            mc.getSingleplayerServer().execute(() -> {
                try { setup(mc.getSingleplayerServer().getPlayerList().getPlayer(owner)); }
                catch (Throwable error) { finish(error); }
            });
        }
        if (started && !finished && mc.player != null) {
            if (maid != null && mc.level != null) for (var entity : mc.level.entitiesForRendering()) {
                if (entity instanceof EntityMaid tracked && tracked.getUUID().equals(maid.getUUID()) && owner.equals(tracked.getOwnerUUID())) clientTracked = true;
            }
            if (!owner.equals(mc.player.getUUID()) || mc.screen != expectedScreen) finish(new AssertionError("Owner player/screen changed: actual="
                    + (mc.screen == null ? "null" : mc.screen.getClass().getName()) + ", expected=" + expectedScreen.getClass().getName() + ", owner=" + mc.player.getUUID()));
            if (ticks > 2200) finish(new AssertionError("Client check timeout"));
        }
    }
    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        player.setHealth(player.getMaxHealth()); player.setAirSupply(300);
        BlockPos pos = player.blockPosition().offset(2, 0, 0);
        for (BlockPos floor : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, -1, 3))) level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        for (BlockPos air : BlockPos.betweenClosed(pos.offset(-3, 0, -3), pos.offset(3, 4, 3))) level.setBlockAndUpdate(air, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos, GuiFixtureMod.MACHINE.get().defaultBlockState());
        maid = InitEntities.MAID.get().create(level); maid.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + 1.5, 0, 0);
        maid.setOwnerUUID(player.getUUID()); maid.setTame(true); maid.setNoAi(true); maid.setInvulnerable(true); level.addFreshEntity(maid);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.FEATHER, 2));
        callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject open = new JsonObject(); open.addProperty("x", pos.getX()); open.addProperty("y", pos.getY()); open.addProperty("z", pos.getZ()); open.addProperty("wait_policy", "AUTO");
        call("open_gui", open).thenAccept(result -> {
            check(result, "opened");
            require(MaidGuiSessionManager.session(maid.getUUID()).actor.openingData().length == 8, "Forge opening data");
            startTick = level.getGameTime();
        }).exceptionally(error -> { finish(error); return null; });
    }
    @SubscribeEvent public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && maid != null) for (Delayed task : List.copyOf(delayed)) {
            if (maid.level().getGameTime() >= task.at) { delayed.remove(task); try { task.task.run(); } catch (Throwable error) { finish(error); } }
        }
        if (!started || finished || !clientTracked || event.phase != TickEvent.Phase.END || maid == null || startTick == 0) return;
        if (maid.level().getGameTime() - startTick < 80) return;
        startTick = 0;
        JsonObject capture = args(); capture.addProperty("visual", true);
        call("inspect_gui", capture).thenCompose(result -> {
            check(result, "ok"); frame = result;
            require(result.has("_gui_image") && result.getAsJsonArray("controls").size() >= 3, "Actual rendered GUI and controls");
            try {
                String data = result.get("_gui_image").getAsString();
                Files.write(Path.of("gui-qa-capture.jpg"), Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1)));
            } catch (Exception error) { throw new RuntimeException(error); }
            return widget("widget:0", "widget", null);
        }).thenCompose(result -> {
            check(result, "ok"); require(mode() == 1, "Standard button server action");
            return capture();
        }).thenCompose(result -> {
            check(result, "ok"); frame = result; return widget("widget:1", "widget", null);
        }).thenCompose(result -> {
            check(result, "ok"); require(mode() == 0, "Adapted custom protocol");
            return widget("widget:0", "widget", null);
        }).thenCompose(result -> {
            check(result, "failed"); require(result.get("error").getAsString().equals("stale_frame_reinspect"), "Stale frame rejected"); return capture();
        }).thenCompose(result -> {
            check(result, "ok"); frame = result; return widget("widget:3", "widget", null);
        }).thenCompose(result -> {
            check(result, "failed"); require(result.get("error").getAsString().contains("custom_protocol_requires_adapter"), "Unknown protocol isolated");
            require(mode() == 0, "Unsupported packet does not mutate server"); return capture();
        }).thenCompose(result -> {
            check(result, "ok"); frame = result;
            JsonObject click = args(); click.addProperty("action", "click"); click.add("frame_id", frame.get("frame_id")); click.add("layout", frame.get("layout"));
            click.addProperty("x", frame.get("gui_width").getAsInt() / 2 - 13); click.addProperty("y", frame.get("gui_height").getAsInt() / 2 - 58);
            return call("gui_action", click);
        }).thenCompose(result -> {
            check(result, "ok"); require(mode() == 1, "Self-drawn control uses original screen click"); return capture();
        }).thenCompose(result -> {
            check(result, "ok"); frame = result; return widget("widget:2", "type", "test filter");
        }).thenCompose(result -> {
            check(result, "ok"); require(result.get("effect").getAsString().equals("client_ui_only"), "Local text input");
            JsonObject transfer = args(); transfer.addProperty("action", "transfer"); transfer.addProperty("slot", 30); transfer.addProperty("to_slot", 0); transfer.addProperty("count", 2);
            return call("gui_action", transfer);
        }).thenCompose(result -> {
            check(result, "ok"); require(result.get("moved_count").getAsInt() == 2, "Maid input transfer");
            JsonObject wait = args(); wait.addProperty("item", "minecraft:paper"); wait.addProperty("count", 2); wait.addProperty("output_slot", 1);
            CompletableFuture<JsonObject> original = MaidGuiSessionManager.call(callback, "wait_gui", "dedup-wait", wait);
            CompletableFuture<JsonObject> duplicate = MaidGuiSessionManager.call(callback, "wait_gui", "dedup-wait", wait);
            return original.thenCombine(duplicate, (a, b) -> { require(a.equals(b), "Pending action deduplication"); return a; });
        }).thenAccept(result -> {
            check(result, "completed"); require(result.get("delivered_count").getAsInt() == 2, "Whole batch delivered");
            require(MaidGuiSessionManager.session(maid.getUUID()) == null, "Completed menu closed");
            previewChecks();
        }).exceptionally(error -> { finish(error); return null; });
    }
    private static void previewChecks() {
        BlockPos pos = maid.blockPosition().offset(0, 0, -1);
        callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject open = new JsonObject(); open.addProperty("x", pos.getX()); open.addProperty("y", pos.getY()); open.addProperty("z", pos.getZ()); open.addProperty("wait_policy", "UNTIL_GOAL");
        call("open_gui", open).thenAccept(result -> {
            check(result, "opened"); var session = MaidGuiSessionManager.session(maid.getUUID());
            session.menu().getSlot(0).set(new ItemStack(Items.FEATHER, 4));
            JsonObject wait = args(); wait.addProperty("item", "minecraft:paper"); wait.addProperty("count", 4); wait.addProperty("output_slot", 1);
            var future = call("wait_gui", wait);
            future.thenAccept(outcome -> {
                check(outcome, "failed"); require(outcome.get("error").getAsString().equals("stopped_by_owner"), "Preview stop ends wait");
                require(session.menu() == session.actor.inventoryMenu, "Stop closes menu");
                require(((GuiFixtureMod.TestMenu) originalMenu).storage.items.getItem(0).getCount() > 0, "Stop retains machine input");
                var reopened = call("open_gui", open).join();
                require(reopened.has("error") && reopened.get("error").getAsString().equals("stopped_by_owner"), "Stopped task cannot restart itself");
                finish(null);
            }).exceptionally(error -> { finish(error); return null; });
            originalMenu = session.menu();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> { expectedScreen = new GuiOperationPreviewScreen(sentinel, maid.getUUID()); mc.setScreen(expectedScreen); });
            delayed.add(new Delayed(maid.level().getGameTime() + 40, () -> {
                require(!future.isDone(), "Preview leaves wait active");
                mc.execute(() -> {
                    try (var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("gui-qa-preview.png")); }
                    catch (Exception error) { finish(error); }
                    expectedScreen.onClose(); expectedScreen = sentinel;
                });
                delayed.add(new Delayed(maid.level().getGameTime() + 20, () -> {
                    require(!future.isDone(), "Closing preview does not stop processing");
                    mc.execute(() -> {
                        expectedScreen = new GuiOperationPreviewScreen(sentinel, maid.getUUID()); mc.setScreen(expectedScreen);
                        expectedScreen.mouseClicked(expectedScreen.width / 2.0 - 55, expectedScreen.height - 16, 0);
                    });
                }));
            }));
        }).exceptionally(error -> { finish(error); return null; });
    }
    private static net.minecraft.world.inventory.AbstractContainerMenu originalMenu;
    private static int mode() { return ((GuiFixtureMod.TestMenu) MaidGuiSessionManager.session(maid.getUUID()).menu()).storage.mode; }
    private static JsonObject args() { JsonObject value = new JsonObject(); value.addProperty("session_id", MaidGuiSessionManager.session(maid.getUUID()).id.toString()); return value; }
    private static CompletableFuture<JsonObject> call(String tool, JsonObject request) { return MaidGuiSessionManager.call(callback, tool, "client-qa-" + sequence++, request); }
    private static CompletableFuture<JsonObject> capture() { JsonObject value = args(); value.addProperty("visual", true); return call("inspect_gui", value); }
    private static CompletableFuture<JsonObject> widget(String id, String action, String text) {
        JsonObject value = args(); value.addProperty("action", action); value.addProperty("widget_id", id);
        value.add("frame_id", frame.get("frame_id")); value.add("layout", frame.get("layout")); if (text != null) value.addProperty("text", text);
        return call("gui_action", value);
    }
    private static void check(JsonObject value, String status) { require(value.get("status").getAsString().equals(status), value.toString()); }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static void finish(Throwable error) {
        if (finished) return; finished = true;
        String result = error == null ? "PASS: render, Forge extra data, standard/custom/self-drawn buttons, unknown protocol firewall, stale frame, text, inventory, duplicate wait, batch collection, readonly preview, preview close, stop, owner context" : "FAIL: " + error;
        if (error != null) error.printStackTrace();
        try { Files.writeString(Path.of("gui-qa-client-result.txt"), result); } catch (Exception ignored) { }
        System.out.println(result); Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }
}
