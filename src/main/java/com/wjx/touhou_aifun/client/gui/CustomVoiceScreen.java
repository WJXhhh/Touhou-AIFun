package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.function.BiConsumer;

public class CustomVoiceScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 156;
    private static final int LABEL_COLOR = 0xFFAAAAAA;

    private final Screen parent;
    private final BiConsumer<String, String> onSave;
    private String initialAudioPath;
    private String initialRefText;
    private EditBox audioPathBox;
    private EditBox refTextBox;

    public CustomVoiceScreen(Screen parent, String initialAudioPath, String initialRefText,
                             BiConsumer<String, String> onSave) {
        super(Component.translatable("gui.touhou_aifun.custom_voice.title"));
        this.parent = parent;
        this.initialAudioPath = StringUtils.defaultString(initialAudioPath);
        this.initialRefText = StringUtils.defaultString(initialRefText);
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        if (this.audioPathBox != null) {
            this.initialAudioPath = this.audioPathBox.getValue();
        }
        if (this.refTextBox != null) {
            this.initialRefText = this.refTextBox.getValue();
        }
        this.clearWidgets();

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        this.audioPathBox = new EditBox(this.font, left + 12, top + 43, PANEL_WIDTH - 116, 18,
                Component.translatable("gui.touhou_aifun.custom_voice.audio_path"));
        this.audioPathBox.setMaxLength(1024);
        this.audioPathBox.setValue(this.initialAudioPath);
        this.addRenderableWidget(this.audioPathBox);

        this.addRenderableWidget(new FlatColorButton(left + PANEL_WIDTH - 96, top + 42, 84, 20,
                Component.translatable("gui.touhou_aifun.custom_voice.browse"), button -> this.chooseAudioFile()));

        this.refTextBox = new EditBox(this.font, left + 12, top + 86, PANEL_WIDTH - 24, 18,
                Component.translatable("gui.touhou_aifun.custom_voice.ref_text"));
        this.refTextBox.setMaxLength(2048);
        this.refTextBox.setValue(this.initialRefText);
        this.addRenderableWidget(this.refTextBox);

        this.addRenderableWidget(new FlatColorButton(left + 12, top + PANEL_HEIGHT - 28, 72, 20,
                Component.translatable("gui.touhou_aifun.custom_voice.clear"), button -> {
            this.onSave.accept(StringUtils.EMPTY, StringUtils.EMPTY);
            this.onClose();
        }));
        this.addRenderableWidget(new FlatColorButton(left + PANEL_WIDTH - 168, top + PANEL_HEIGHT - 28, 76, 20,
                Component.translatable("selectWorld.edit.save"), button -> {
            this.onSave.accept(StringUtils.trimToEmpty(this.audioPathBox.getValue()),
                    StringUtils.trimToEmpty(this.refTextBox.getValue()));
            this.onClose();
        }));
        this.addRenderableWidget(new FlatColorButton(left + PANEL_WIDTH - 84, top + PANEL_HEIGHT - 28, 72, 20,
                Component.translatable("gui.cancel"), button -> this.onClose()));
    }

    private void chooseAudioFile() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(2);
            filters.put(stack.UTF8("*.wav"));
            filters.put(stack.UTF8("*.mp3"));
            filters.flip();

            String currentPath = StringUtils.trimToEmpty(this.audioPathBox.getValue());
            String defaultPath = StringUtils.isBlank(currentPath)
                    ? Path.of(".").toAbsolutePath().normalize().toString()
                    : currentPath;
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("gui.touhou_aifun.custom_voice.choose_file").getString(),
                    defaultPath,
                    filters,
                    "WAV/MP3",
                    false
            );
            if (StringUtils.isNotBlank(selected)) {
                this.audioPathBox.setValue(selected);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xEE181818);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 10, 0xFFF3EFE0);
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_aifun.custom_voice.audio_path"),
                left + 12, top + 31, LABEL_COLOR, false);
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_aifun.custom_voice.ref_text_optional"),
                left + 12, top + 74, LABEL_COLOR, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        this.audioPathBox.tick();
        this.refTextBox.tick();
        super.tick();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
