package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.vision.VisionSite;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** TLM-styled editor for one visual provider. */
public final class VisionSiteEditorScreen extends Screen {
    private static final int BASE_WIDTH = 400;
    private static final int BASE_HEIGHT = 230;
    private static final int LABEL_COLOR = 0xFF777777;

    private final VisionSettingsScreen parent;
    private final VisionSite site;
    private final boolean readOnly;
    private int startX;
    private int startY;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;

    public VisionSiteEditorScreen(VisionSettingsScreen parent, VisionSite site, boolean readOnly) {
        super(Component.translatable("gui.touhou_aifun.vision.editor", site.displayName()));
        this.parent = parent;
        this.site = site;
        this.readOnly = readOnly;
    }

    @Override
    protected void init() {
        clearWidgets();
        startX = (width - BASE_WIDTH) / 2;
        startY = (height - BASE_HEIGHT) / 2;
        int x = startX + 12;
        int fieldWidth = BASE_WIDTH - 24;
        endpoint = addInput(x, startY + 52, fieldWidth,
                Component.translatable("gui.touhou_aifun.vision.endpoint"), site.endpoint(), 1024, false);
        model = addInput(x, startY + 97, fieldWidth,
                Component.translatable("gui.touhou_aifun.vision.model"), site.model(), 256, false);
        apiKey = addInput(x, startY + 142, fieldWidth,
                Component.translatable("gui.touhou_aifun.vision.api_key"), "", 4096, true);
        apiKey.setSuggestion(Component.translatable("gui.touhou_aifun.vision.api_key_hint").getString());

        int bottomY = startY + BASE_HEIGHT - 24;
        FlatColorButton clearKey = new FlatColorButton(startX + 12, bottomY, 120, 20,
                Component.translatable("gui.touhou_aifun.vision.clear_api_key"), button -> clearApiKey());
        clearKey.active = !readOnly && site.apiKeyPresent();
        addRenderableWidget(clearKey);
        FlatColorButton save = new FlatColorButton(startX + BASE_WIDTH - 200, bottomY, 90, 20,
                Component.translatable("gui.touhou_aifun.vision.save"), button -> save());
        save.active = !readOnly;
        addRenderableWidget(save);
        addRenderableWidget(new FlatColorButton(startX + BASE_WIDTH - 102, bottomY, 90, 20,
                CommonComponents.GUI_BACK, button -> onClose()));
    }

    private EditBox addInput(int x, int y, int width, Component label, String value, int maxLength, boolean secret) {
        EditBox box = new EditBox(font, x + 6, y, width - 12, 16, label);
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setEditable(!readOnly);
        if (secret) {
            box.setFormatter((text, pos) -> FormattedCharSequence.forward("·".repeat(text.length()), Style.EMPTY));
        }
        addWidget(box);
        return box;
    }

    private void save() {
        if (readOnly) return;
        site.setEndpoint(endpoint.getValue());
        site.setModel(model.getValue());
        site.setApiKey(apiKey.getValue());
        parent.saveSite(site);
        onClose();
    }

    private void clearApiKey() {
        if (readOnly) return;
        site.setApiKey("");
        parent.clearSiteApiKey(site);
        onClose();
    }

    @Override
    public void tick() {
        endpoint.tick();
        model.tick();
        apiKey.tick();
        super.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xc0101010, 0xc0101010);
        graphics.drawCenteredString(font, title, startX + BASE_WIDTH / 2, startY + 4, 0xFFF3EFE0);
        renderInput(graphics, endpoint, mouseX, mouseY, partialTick);
        renderInput(graphics, model, mouseX, mouseY, partialTick);
        renderInput(graphics, apiKey, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.translatable(site.apiKeyPresent()
                        ? "gui.touhou_aifun.vision.api_key_configured"
                        : "gui.touhou_aifun.vision.api_key_missing"),
                apiKey.getX(), apiKey.getY() + apiKey.getHeight() + 5,
                site.apiKeyPresent() ? 0xFF55AA55 : 0xFFFFAA55, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderInput(GuiGraphics graphics, EditBox box, int mouseX, int mouseY, float partialTick) {
        int x = box.getX() - 6;
        int y = box.getY() - 6;
        graphics.drawString(font, box.getMessage(), x + 2, y - 12, LABEL_COLOR, false);
        graphics.fill(x, y, x + box.getWidth() + 12, y + box.getHeight() + 3, 0xAA111111);
        box.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
