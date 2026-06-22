package team.cfpa.touhoustepfun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;

import java.util.function.Consumer;

public final class TTSInstructionScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 112;
    private static final int LABEL_COLOR = 0xFFAAAAAA;

    private final Screen parent;
    private final Consumer<String> onSave;
    private String initialInstruction;
    private EditBox instructionBox;

    public TTSInstructionScreen(Screen parent, String initialInstruction, Consumer<String> onSave) {
        super(Component.translatable("gui.touhou_stepfun.tts_instruction.title"));
        this.parent = parent;
        this.initialInstruction = StringUtils.defaultString(initialInstruction);
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        if (this.instructionBox != null) {
            this.initialInstruction = this.instructionBox.getValue();
        }
        this.clearWidgets();

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        this.instructionBox = new EditBox(this.font, left + 12, top + 43, PANEL_WIDTH - 24, 18,
                Component.translatable("gui.touhou_stepfun.tts_instruction.input"));
        this.instructionBox.setMaxLength(200);
        this.instructionBox.setValue(this.initialInstruction);
        this.addRenderableWidget(this.instructionBox);

        this.addRenderableWidget(new FlatColorButton(left + 12, top + PANEL_HEIGHT - 28, 72, 20,
                Component.translatable("gui.touhou_stepfun.custom_voice.clear"), button -> {
            this.onSave.accept(StringUtils.EMPTY);
            this.onClose();
        }));
        this.addRenderableWidget(new FlatColorButton(left + PANEL_WIDTH - 168, top + PANEL_HEIGHT - 28, 76, 20,
                Component.translatable("selectWorld.edit.save"), button -> {
            this.onSave.accept(StringUtils.trimToEmpty(this.instructionBox.getValue()));
            this.onClose();
        }));
        this.addRenderableWidget(new FlatColorButton(left + PANEL_WIDTH - 84, top + PANEL_HEIGHT - 28, 72, 20,
                Component.translatable("gui.cancel"), button -> this.onClose()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xEE181818);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 10, 0xFFF3EFE0);
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_stepfun.tts_instruction.label"),
                left + 12, top + 31, LABEL_COLOR, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        this.instructionBox.tick();
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
