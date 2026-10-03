package com.wjx.touhou_aifun.client.gui.automation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.wjx.touhou_aifun.mixin.client.MinecraftRenderTargetAccessor;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.*;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Detached screens run only on the client render thread, under a scoped packet firewall. */
public final class GuiClientRuntime {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final List<MaidGuiClientAdapter> ADAPTERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void registerAdapter(MaidGuiClientAdapter adapter) { ADAPTERS.add(0, Objects.requireNonNull(adapter)); }
    private static State active;
    private static final class State {
        final UUID session, maid;
        final LocalPlayer actor;
        Screen screen;
        AbstractContainerMenu menu;
        final JsonArray actions = new JsonArray();
        String failure = "";
        long frame;
        String layout = "";
        State(UUID session, UUID maid, LocalPlayer actor) { this.session = session; this.maid = maid; this.actor = actor; }
    }
    private GuiClientRuntime() { }
    public static boolean isolated() { return active != null; }
    public static void replaceScreen(Screen screen) {
        if (active == null) return;
        if (screen != null && (!(screen instanceof MenuAccess<?> access) || access.getMenu() != active.menu))
            throw new IllegalArgumentException("client_only_screen_not_supported");
        active.screen = screen;
        Minecraft mc = Minecraft.getInstance(); mc.screen = screen;
        if (screen != null) screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }
    /** Injected into Connection#send, including mod sends through a retained real connection. */
    public static boolean intercept(Packet<?> packet) {
        if (active == null || !RenderSystem.isOnRenderThread()) return false;
        JsonObject action = new JsonObject();
        if (packet instanceof ServerboundContainerClickPacket click) {
            if (click.getContainerId() != active.menu.containerId || click.getSlotNum() < 0
                    || click.getButtonNum() < 0 || click.getButtonNum() > 1
                    || click.getClickType() != ClickType.PICKUP && click.getClickType() != ClickType.QUICK_MOVE) {
                active.failure = "unsupported_container_click"; return true;
            }
            action.addProperty("action", click.getClickType() == ClickType.QUICK_MOVE ? "quick_move" : "click_slot");
            action.addProperty("slot", click.getSlotNum()); action.addProperty("button", click.getButtonNum());
        } else if (packet instanceof ServerboundContainerButtonClickPacket button) {
            if (button.getContainerId() != active.menu.containerId) { active.failure = "wrong_container"; return true; }
            action.addProperty("action", "button"); action.addProperty("button_id", button.getButtonId());
        } else if (packet instanceof ServerboundRenameItemPacket rename) {
            action.addProperty("action", "rename"); action.addProperty("text", rename.getName());
        } else if (packet instanceof ServerboundSelectTradePacket trade) {
            action.addProperty("action", "trade"); action.addProperty("trade_index", trade.getItem());
        } else if (packet instanceof ServerboundContainerClosePacket close) {
            if (close.getContainerId() != active.menu.containerId) { active.failure = "wrong_container"; return true; }
            action.addProperty("action", "close_menu");
        } else if (packet instanceof ServerboundCustomPayloadPacket custom) {
            FriendlyByteBuf data = custom.getData();
            if (data.readableBytes() > 4096) { active.failure = "custom_packet_too_large"; return true; }
            byte[] bytes = new byte[data.readableBytes()]; data.getBytes(data.readerIndex(), bytes);
            action.addProperty("action", "custom_packet"); action.addProperty("channel", custom.getIdentifier().toString());
            action.addProperty("payload", Base64.getEncoder().encodeToString(bytes));
        } else { active.failure = "unsupported_gui_packet:" + packet.getClass().getSimpleName(); return true; }
        if (active.actions.size() >= 16) active.failure = "gui_action_batch_too_large";
        else active.actions.add(action);
        return true;
    }
    public static void handle(AIFunGuiRequestMessage request) {
        Minecraft mc = Minecraft.getInstance();
        if (request.operation().equals("close")) { STATES.remove(request.session()); GuiOperationPreviewScreen.closed(request.maid()); return; }
        EntityMaid maid = null;
        if (mc.level != null) for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EntityMaid candidate && candidate.getUUID().equals(request.maid())) { maid = candidate; break; }
        }
        if (mc.player == null || maid == null || !Objects.equals(maid.getOwnerUUID(), mc.player.getUUID())) { fail(request, "maid_not_tracked_or_owned"); return; }
        try {
            JsonObject args = JsonParser.parseString(request.json()).getAsJsonObject();
            if (args.has("target_position")) {
                JsonArray position = args.getAsJsonArray("target_position");
                if (!mc.level.hasChunkAt(new net.minecraft.core.BlockPos(position.get(0).getAsInt(), position.get(1).getAsInt(), position.get(2).getAsInt())))
                    throw new IllegalArgumentException("target_not_loaded_on_client");
            }
            State state = STATES.get(request.session());
            if (state == null) {
                if (STATES.size() >= 8) { fail(request, "gui_client_capacity"); return; }
                Connection ownerConnection = mc.player.connection.getConnection();
                Connection connection = new Connection(PacketFlow.CLIENTBOUND) {
                    @Override public void send(Packet<?> packet) { intercept(packet); }
                    @Override public void send(Packet<?> packet, PacketSendListener listener) { intercept(packet); }
                    @Override public io.netty.channel.Channel channel() { return ownerConnection.channel(); }
                };
                GameProfile profile = new GameProfile(request.session(), "[AIFunMaid]");
                ClientPacketListener listener = new ClientPacketListener(mc, null, connection, null, profile, null) {
                    @Override public net.minecraft.world.item.crafting.RecipeManager getRecipeManager() { return mc.level.getRecipeManager(); }
                    @Override public void close() { }
                };
                LocalPlayer actor = new LocalPlayer(mc, mc.level, listener, new StatsCounter(), new ClientRecipeBook(), false, false);
                actor.moveTo(maid.getX(), maid.getY(), maid.getZ(), maid.getYRot(), maid.getXRot());
                state = new State(request.session(), request.maid(), actor); STATES.put(request.session(), state);
            }
            State current = state;
            LocalPlayer oldPlayer = mc.player; Screen oldScreen = mc.screen;
            active = current; current.actions.asList().clear(); current.failure = "";
            try {
                mc.player = current.actor; mc.screen = current.screen;
                mirror(current, request.mirror()); mc.screen = current.screen;
                // Initialization/slot listeners may emit vanilla UI synchronization packets.
                // Observation never executes those initialization side effects on the server.
                current.actions.asList().clear(); current.failure = "";
                if (request.operation().equals("input")) {
                    if (current.frame == 0 || current.frame != args.get("frame_id").getAsLong()
                            || !current.layout.equals(args.get("layout").getAsString()) || !current.layout.equals(layout(current.screen))) throw new IllegalArgumentException("stale_frame_reinspect");
                    input(current, args);
                    if (!current.failure.isBlank()) throw new IllegalArgumentException(current.failure);
                    JsonObject result = new JsonObject(); result.add("actions", current.actions.deepCopy());
                    mc.tell(() -> reply(request, result, new byte[0]));
                } else {
                    Rendered rendered = render(current, request.operation().equals("preview"));
                    if (!current.failure.isBlank() || current.actions.size() > 0) throw new IllegalArgumentException("screen_render_has_network_side_effects");
                    if (request.operation().equals("preview")) {
                        GuiOperationPreviewScreen.update(request.maid(), args, rendered.image);
                    } else {
                        current.frame++; current.layout = layout(current.screen);
                        JsonObject result = new JsonObject(); result.addProperty("frame_id", current.frame);
                        result.addProperty("layout", current.layout); result.addProperty("width", current.screen.width); result.addProperty("height", current.screen.height);
                        result.add("controls", controls(current.screen));
                        NativeImage image = rendered.image;
                        CompletableFuture.supplyAsync(() -> { try { return jpeg(image); } finally { image.close(); } })
                                .whenComplete((bytes, error) -> mc.execute(() -> {
                                    if (STATES.get(request.session()) != current) return;
                                    if (error != null) fail(request, "gui_encode_failed"); else reply(request, result, bytes);
                                }));
                    }
                }
            } finally { active = null; mc.player = oldPlayer; mc.screen = oldScreen; }
        } catch (Throwable error) {
            fail(request, error.getMessage() == null ? "background_screen_unsupported" : error.getMessage());
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void mirror(State state, byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            var type = BuiltInRegistries.MENU.get(buffer.readResourceLocation()); int id = buffer.readVarInt(); var title = buffer.readComponent();
            byte[] extra = buffer.readByteArray(32600);
            if (state.menu == null || state.menu.containerId != id || state.menu.getType() != type) {
                FriendlyByteBuf opening = new FriendlyByteBuf(Unpooled.wrappedBuffer(extra));
                try { state.menu = type.create(id, state.actor.getInventory(), opening); } finally { opening.release(); }
                state.actor.containerMenu = state.menu;
                var constructor = MenuScreens.getScreenFactory(type, Minecraft.getInstance(), id, title).orElseThrow(() -> new IllegalArgumentException("screen_factory_missing"));
                state.screen = (Screen) ((MenuScreens.ScreenConstructor) constructor).create(state.menu, state.actor.getInventory(), title);
                if (!(state.screen instanceof MenuAccess<?> access) || access.getMenu() != state.menu) throw new IllegalArgumentException("client_only_screen_not_supported");
                state.screen.init(Minecraft.getInstance(), Minecraft.getInstance().getWindow().getGuiScaledWidth(), Minecraft.getInstance().getWindow().getGuiScaledHeight());
                state.frame = 0;
            }
            int slots = buffer.readVarInt(); if (slots != state.menu.slots.size() || slots > 512) throw new IllegalArgumentException("client_menu_layout_mismatch");
            for (int i = 0; i < slots; i++) state.menu.getSlot(i).set(buffer.readItem());
            state.menu.setCarried(buffer.readItem());
            for (int i = 0; i < 41; i++) state.actor.getInventory().setItem(i, buffer.readItem());
            int data = buffer.readVarInt(); if (data < 0 || data > 512) throw new IllegalArgumentException("invalid_menu_data");
            for (int i = 0; i < data; i++) state.menu.setData(i, buffer.readInt());
            if (buffer.readBoolean()) {
                MerchantOffers offers = new MerchantOffers(buffer.readNbt());
                if (state.menu instanceof MerchantMenu merchant) merchant.setOffers(offers);
            }
            JsonObject custom = JsonParser.parseString(buffer.readUtf(16384)).getAsJsonObject();
            if (custom.size() > 0) {
                boolean applied = false;
                for (MaidGuiClientAdapter adapter : ADAPTERS) if (adapter.supports(state.menu)) { adapter.apply(state.menu, state.screen, custom); applied = true; break; }
                if (!applied) throw new IllegalArgumentException("custom_screen_state_requires_client_adapter");
            }
        } finally { buffer.release(); }
    }
    private static void input(State state, JsonObject args) {
        String action = args.get("action").getAsString();
        double x = args.has("x") ? args.get("x").getAsDouble() : 0, y = args.has("y") ? args.get("y").getAsDouble() : 0;
        int button = args.has("button") ? args.get("button").getAsInt() : 0;
        if (button < 0 || button > 1) throw new IllegalArgumentException("invalid_mouse_button");
        if (action.equals("widget") || action.equals("type") && args.has("widget_id")) {
            List<AbstractWidget> widgets = widgets(state.screen);
            int index = Integer.parseInt(args.get("widget_id").getAsString().replace("widget:", ""));
            if (index < 0 || index >= widgets.size()) throw new IllegalArgumentException("widget_unavailable");
            AbstractWidget widget = widgets.get(index);
            if (!widget.active || !widget.visible) throw new IllegalArgumentException("widget_disabled");
            x = widget.getX() + widget.getWidth() / 2.0; y = widget.getY() + widget.getHeight() / 2.0;
            if (action.equals("type")) {
                if (!(widget instanceof EditBox box)) throw new IllegalArgumentException("text_widget_requires_adapter");
                String text = args.get("text").getAsString(); if (text.length() > 256) throw new IllegalArgumentException("text_too_long");
                box.setValue(text); return;
            }
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= state.screen.width || y >= state.screen.height) throw new IllegalArgumentException("coordinate_out_of_bounds");
        if (action.equals("key")) {
            int key = args.get("key_code").getAsInt();
            if (key < 32 || key > 348) throw new IllegalArgumentException("invalid_key_code");
            state.screen.keyPressed(key, 0, 0);
        } else if (action.equals("type")) {
            String text = args.get("text").getAsString(); if (text.length() > 256) throw new IllegalArgumentException("text_too_long");
            state.screen.mouseClicked(x, y, 0); state.screen.mouseReleased(x, y, 0);
            for (char character : text.toCharArray()) if (!state.screen.charTyped(character, 0)) throw new IllegalArgumentException("screen_did_not_accept_text");
        } else if (action.equals("scroll")) {
            double scroll = args.has("scroll") ? args.get("scroll").getAsDouble() : 1;
            if (!Double.isFinite(scroll) || Math.abs(scroll) > 10) throw new IllegalArgumentException("invalid_scroll");
            state.screen.mouseScrolled(x, y, scroll);
        } else { state.screen.mouseClicked(x, y, button); state.screen.mouseReleased(x, y, button); }
    }
    private record Rendered(NativeImage image) { }
    private static Rendered render(State state, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        int width = state.screen.width, height = state.screen.height;
        float scale = Math.min(2F, Math.min(2048F / width, 1536F / height));
        TextureTarget target = null;
        RenderTarget old = mc.getMainRenderTarget(); int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int[] scissorBox = new int[4]; GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        int srcRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
        int drawBuffer = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING), readBuffer = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        var shader = RenderSystem.getShader(); int[] textures = new int[12];
        for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        float[] color = RenderSystem.getShaderColor().clone();
        RenderSystem.backupProjectionMatrix(); RenderSystem.getModelViewStack().pushPose();
        try {
            target = new TextureTarget(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), true, Minecraft.ON_OSX);
            ((MinecraftRenderTargetAccessor) mc).touhouAIFun$setMainRenderTarget(target);
            target.setClearColor(0.08F, 0.08F, 0.1F, 1); target.clear(Minecraft.ON_OSX); target.bindWrite(true);
            RenderSystem.getModelViewStack().setIdentity(); RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, width, height, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.disableDepthTest(); RenderSystem.disableScissor();
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            state.screen.render(graphics, -1, -1, 0); graphics.flush();
            return new Rendered(Screenshot.takeScreenshot(target));
        } finally {
            if (target != null) target.destroyBuffers();
            ((MinecraftRenderTargetAccessor) mc).touhouAIFun$setMainRenderTarget(old);
            old.bindWrite(true);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, drawBuffer);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, readBuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.restoreProjectionMatrix(); RenderSystem.getModelViewStack().popPose(); RenderSystem.applyModelViewMatrix();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]); else RenderSystem.disableScissor();
            RenderSystem.setShader(() -> shader); for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
        }
    }
    private static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> widgets = new ArrayList<>();
        for (GuiEventListener child : screen.children()) if (child instanceof AbstractWidget widget) widgets.add(widget);
        return widgets;
    }
    private static String layout(Screen screen) {
        StringBuilder value = new StringBuilder(screen.getClass().getName()).append(screen.width).append(':').append(screen.height);
        for (AbstractWidget widget : widgets(screen)) value.append('|').append(widget.getX()).append(',').append(widget.getY())
                .append(',').append(widget.getWidth()).append(',').append(widget.getHeight()).append(',').append(widget.active).append(',').append(widget.visible).append(',').append(widget.getMessage().getString());
        return Integer.toHexString(value.toString().hashCode());
    }
    private static JsonArray controls(Screen screen) {
        JsonArray values = new JsonArray(); List<AbstractWidget> widgets = widgets(screen);
        for (int i = 0; i < Math.min(24, widgets.size()); i++) {
            AbstractWidget widget = widgets.get(i); JsonObject value = new JsonObject();
            String label = widget.getMessage().getString();
            value.addProperty("id", "widget:" + i); value.addProperty("label", label.substring(0, Math.min(64, label.length())));
            value.addProperty("kind", widget instanceof EditBox ? "text" : "button"); value.addProperty("enabled", widget.active && widget.visible);
            if (widget instanceof EditBox box) value.addProperty("value", box.getValue().substring(0, Math.min(128, box.getValue().length())));
            value.addProperty("x", widget.getX()); value.addProperty("y", widget.getY()); value.addProperty("width", widget.getWidth()); value.addProperty("height", widget.getHeight()); values.add(value);
        }
        return values;
    }
    private static byte[] jpeg(NativeImage image) {
        try {
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int abgr = image.getPixelRGBA(x, y); rgb.setRGB(x, y, (abgr & 255) << 16 | abgr & 0xff00 | (abgr >> 16 & 255));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(rgb, "jpeg", bytes);
            if (bytes.size() > 900 * 1024) throw new IllegalArgumentException("gui_image_too_large"); return bytes.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static void fail(AIFunGuiRequestMessage request, String reason) {
        if (request.operation().equals("preview")) { GuiOperationPreviewScreen.failed(reason); return; }
        JsonObject result = new JsonObject(); result.addProperty("status", "failed"); result.addProperty("error", reason.substring(0, Math.min(256, reason.length()))); reply(request, result, new byte[0]);
    }
    private static void reply(AIFunGuiRequestMessage request, JsonObject result, byte[] bytes) {
        String json = result.toString(); if (json.length() > 8192 || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12 * 1024) { fail(request, "gui_reply_too_large"); return; }
        int parts = Math.max(1, (bytes.length + 16383) / 16384);
        for (int i = 0; i < parts; i++) AIFunNetwork.sendGuiResult(new AIFunGuiResultMessage(request.request(), request.session(), request.maid(), json, i, parts,
                Arrays.copyOfRange(bytes, Math.min(bytes.length, i * 16384), Math.min(bytes.length, (i + 1) * 16384))));
    }
    public static void clear() { STATES.clear(); }
}
