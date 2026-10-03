package com.wjx.touhou_aifun.client.gui.automation;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import java.util.UUID;

/** Read-only preview; closing this screen does not stop the maid's session. */
public final class GuiOperationPreviewScreen extends Screen {
    private final Screen parent;
    private final UUID maid;
    private ResourceLocation texture;
    private int imageWidth, imageHeight, ticks;
    private String status = "";
    private String processLine = "", actionLine = "";
    public GuiOperationPreviewScreen(Screen parent, UUID maid) {
        super(Component.translatable("gui.touhou_aifun.gui_preview.title")); this.parent = parent; this.maid = maid;
    }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.touhou_aifun.gui_preview.stop"), button -> AIFunNetwork.requestGuiPreview(maid, true))
                .bounds(width / 2 - 105, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose()).bounds(width / 2 + 5, height - 26, 100, 20).build());
        AIFunNetwork.requestGuiPreview(maid, false);
    }
    @Override public void tick() { if (++ticks % 20 == 0) AIFunNetwork.requestGuiPreview(maid, false); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { if (current == this) current = null; if (texture != null) { minecraft.getTextureManager().release(texture); texture = null; } }
    @Override public void render(GuiGraphics graphics, int x, int y, float partial) {
        renderBackground(graphics); graphics.drawCenteredString(font, title, width / 2, 8, 0xffffff);
        if (texture != null) {
            float scale = Math.min((width - 20F) / imageWidth, (height - 110F) / imageHeight);
            int w = (int) (imageWidth * scale), h = (int) (imageHeight * scale);
            graphics.blit(texture, (width - w) / 2, 26, w, h, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        }
        graphics.fill(5, height - 82, width - 5, height - 34, 0xee101018);
        graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 76, 0xcccccc);
        graphics.drawCenteredString(font, Component.literal(processLine), width / 2, height - 64, 0xcccccc);
        graphics.drawCenteredString(font, Component.literal(actionLine), width / 2, height - 52, 0xcccccc);
        super.render(graphics, x, y, partial);
    }
    public static void update(UUID maid, JsonObject state, NativeImage image) {
        Minecraft mc = Minecraft.getInstance();
        // During background capture mc.screen temporarily points to the detached menu. The
        // foreground preview is kept separately by the runtime's restoration scope.
        GuiOperationPreviewScreen preview = current;
        if (preview == null || !preview.maid.equals(maid)) { image.close(); return; }
        if (preview.texture != null) mc.getTextureManager().release(preview.texture);
        preview.imageWidth = image.getWidth(); preview.imageHeight = image.getHeight();
        preview.texture = mc.getTextureManager().register("aifun_gui_preview", new DynamicTexture(image));
        String process = state.has("process") ? state.getAsJsonObject("process").get("status").getAsString() : "UNKNOWN";
        String eta = state.has("process") && state.getAsJsonObject("process").get("remainingTicks").getAsLong() >= 0
                ? Component.translatable("gui.touhou_aifun.gui_preview.remaining", state.getAsJsonObject("process").get("remainingTicks").getAsLong() / 20).getString() : "";
        preview.status = Component.translatable("gui.touhou_aifun.gui_preview.policy." + state.get("wait_policy").getAsString()).getString();
        preview.processLine = Component.translatable("gui.touhou_aifun.gui_preview.process." + process).getString() + eta;
        String action = state.get("last_action").getAsString();
        String key = "gui.touhou_aifun.gui_preview.action." + action;
        if (net.minecraft.locale.Language.getInstance().has(key)) action = Component.translatable(key).getString();
        preview.actionLine = Component.translatable("gui.touhou_aifun.gui_preview.recent", action).getString();
    }
    private static GuiOperationPreviewScreen current;
    @Override public void added() { current = this; }
    public static void closed(UUID maid) { if (current != null && current.maid.equals(maid)) current.status = Component.translatable("gui.touhou_aifun.gui_preview.closed").getString(); }
    public static void failed(String reason) { if (current != null) current.status = reason; }
}
