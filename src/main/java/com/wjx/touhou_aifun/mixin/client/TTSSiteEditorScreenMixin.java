package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.TTSSiteEditorScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.util.Rectangle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wjx.touhou_aifun.client.gui.TTSInstructionScreen;
import com.wjx.touhou_aifun.client.gui.VoicePresetRowState;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetCapabilities;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(value = TTSSiteEditorScreen.class, remap = false)
public abstract class TTSSiteEditorScreenMixin {
    private static final int MODE_BUTTON_WIDTH = 24;
    private static final int MODE_BUTTON_GAP = 2;
    private static final int MAX_EDITOR_WIDTH = 760;

    @Shadow
    @Final
    private TTSSiteFormLayout layout;

    @Shadow
    @Final
    private List<?> modelRows;

    @Shadow
    private Rectangle modelArea;

    @Shadow
    private int modelScrollOffset;

    @Unique
    private final Map<Object, VoicePresetRowState> touhouAIFun$rowStates = new WeakHashMap<>();

    @ModifyConstant(method = "init", constant = @Constant(intValue = 400), remap = true)
    private int touhouAIFun$editorWidthInInit(int original) {
        return this.touhouAIFun$getEditorWidth();
    }

    @ModifyConstant(method = "init", constant = @Constant(intValue = 376), remap = true)
    private int touhouAIFun$contentWidthInInit(int original) {
        return this.touhouAIFun$getEditorWidth() - 24;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 400), remap = true)
    private int touhouAIFun$editorWidthInRender(int original) {
        return this.touhouAIFun$getEditorWidth();
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 200), remap = true)
    private int touhouAIFun$editorCenterInRender(int original) {
        return this.touhouAIFun$getEditorWidth() / 2;
    }

    @Unique
    private int touhouAIFun$getEditorWidth() {
        TTSSiteEditorScreen screen = (TTSSiteEditorScreen) (Object) this;
        return Math.max(1, Math.min(MAX_EDITOR_WIDTH, screen.width - 32));
    }

    @Inject(method = "createModelRows", at = @At("TAIL"))
    private void touhouAIFun$addVoiceModeButtons(int left, int contentWidth, CallbackInfo ci) {
        if (!(this.layout instanceof VoicePresetCapabilities capabilities)) {
            return;
        }
        int visibleCount = Math.max(1, (int) ((this.modelArea.h - 4) / 22));
        int endIndex = Math.min(this.modelRows.size(), this.modelScrollOffset + visibleCount);
        for (int rowIndex = this.modelScrollOffset; rowIndex < endIndex; rowIndex++) {
            Object row = this.modelRows.get(rowIndex);
            ModelRowAccessor accessor = (ModelRowAccessor) row;
            EditBox idBox = accessor.touhouAIFun$getIdBox();
            EditBox nameBox = accessor.touhouAIFun$getNameBox();
            if (idBox == null || nameBox == null) {
                continue;
            }

            VoicePresetRowState state = this.touhouAIFun$rowStates.get(row);
            if (state == null) {
                state = VoicePresetRowState.fromStoredValue(idBox.getValue());
                this.touhouAIFun$rowStates.put(row, state);
            } else {
                state.syncVisibleValue(idBox.getValue());
            }

            int buttonCount = 1 + (capabilities.supportsVoiceDesign() ? 1 : 0)
                    + (capabilities.supportsReferenceVoice() ? 1 : 0)
                    + (capabilities.supportsSynthesisInstruction() ? 1 : 0);
            int buttonsWidth = buttonCount * MODE_BUTTON_WIDTH + (buttonCount - 1) * MODE_BUTTON_GAP;
            int deleteButtonX = left + contentWidth - 24;
            int buttonX = deleteButtonX - 6 - buttonsWidth;
            int buttonY = idBox.getY() - 6;

            int inputLeft = left + 6;
            int inputWidth = Math.max(120, buttonX - 6 - inputLeft);
            int inputGap = 10;
            int idWidth = Math.max(72, (inputWidth - inputGap) * 3 / 5);
            int nameWidth = Math.max(64, inputWidth - inputGap - idWidth);
            idBox.setX(inputLeft);
            idBox.setWidth(idWidth);
            nameBox.setX(inputLeft + idWidth + inputGap);
            nameBox.setWidth(nameWidth);
            state.applyToBox(idBox);

            if (state.mode() == VoicePresetSpec.Mode.REFERENCE_SAMPLE) {
                VoicePresetRowState refState = state;
                EditBox refIdBox = idBox;
                int browseWidth = 44;
                int browseGap = 2;
                int adjustedIdWidth = Math.max(60, idWidth - browseWidth - browseGap);
                idBox.setWidth(adjustedIdWidth);
                int browseX = inputLeft + adjustedIdWidth + browseGap;
                nameBox.setX(browseX + browseWidth + inputGap);
                nameBox.setWidth(Math.max(60, nameWidth - browseWidth - browseGap));
                FlatColorButton browseBtn = new FlatColorButton(
                        browseX, idBox.getY() - 6,
                        browseWidth, 18,
                        Component.translatable("gui.touhou_aifun.custom_voice.browse"),
                        pressed -> touhouAIFun$chooseAudioFile(refState, refIdBox));
                ((TTSSiteEditorScreen) (Object) this).addRenderableWidget(browseBtn);
            }

            int index = 0;
            this.touhouAIFun$addModeButton(row, state, idBox, buttonX, buttonY, index++,
                    VoicePresetSpec.Mode.DIRECT_ID, "ID", "gui.touhou_aifun.voice_mode.id");
            if (capabilities.supportsVoiceDesign()) {
                this.touhouAIFun$addModeButton(row, state, idBox, buttonX, buttonY, index++,
                        VoicePresetSpec.Mode.VOICE_DESIGN, "设", "gui.touhou_aifun.voice_mode.design");
            }
            if (capabilities.supportsReferenceVoice()) {
                this.touhouAIFun$addModeButton(row, state, idBox, buttonX, buttonY, index++,
                        VoicePresetSpec.Mode.REFERENCE_SAMPLE, "样", "gui.touhou_aifun.voice_mode.reference");
            }
            if (capabilities.supportsSynthesisInstruction()) {
                this.touhouAIFun$addInstructionButton(state, idBox, buttonX, buttonY, index++);
            }
        }
    }

    @Inject(method = "buildModels", at = @At("RETURN"), cancellable = true)
    private void touhouAIFun$encodeVoiceModes(CallbackInfoReturnable<Map<String, String>> cir) {
        if (!(this.layout instanceof VoicePresetCapabilities)) {
            return;
        }
        Map<String, String> models = new LinkedHashMap<>();
        for (Object row : this.modelRows) {
            ModelRowAccessor accessor = (ModelRowAccessor) row;
            EditBox idBox = accessor.touhouAIFun$getIdBox();
            EditBox nameBox = accessor.touhouAIFun$getNameBox();
            String visibleValue = StringUtils.trimToEmpty(idBox != null ? idBox.getValue() : accessor.touhouAIFun$getId());
            String name = StringUtils.trimToEmpty(nameBox != null ? nameBox.getValue() : accessor.touhouAIFun$getName());
            if (StringUtils.isBlank(visibleValue) || StringUtils.isBlank(name)) {
                continue;
            }
            VoicePresetRowState state = this.touhouAIFun$rowStates.computeIfAbsent(row,
                    ignored -> VoicePresetRowState.fromStoredValue(visibleValue));
            state.syncVisibleValue(visibleValue);
            String storedValue = state.toSpec().encode();
            if (!models.containsKey(storedValue)) {
                models.put(storedValue, name);
            }
        }
        cir.setReturnValue(models);
    }

    @Unique
    private void touhouAIFun$chooseAudioFile(VoicePresetRowState state, EditBox idBox) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(2);
            filters.put(stack.UTF8("*.wav"));
            filters.put(stack.UTF8("*.mp3"));
            filters.flip();
            String currentPath = StringUtils.trimToEmpty(idBox.getValue());
            String defaultPath = StringUtils.isBlank(currentPath)
                    ? Path.of(".").toAbsolutePath().normalize().toString()
                    : currentPath;
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    "选择参考音频",
                    defaultPath,
                    filters,
                    "WAV/MP3",
                    false
            );
            if (StringUtils.isNotBlank(selected)) {
                idBox.setValue(selected);
            }
        }
    }

    @Unique
    private FlatColorButton touhouAIFun$addModeButton(Object row, VoicePresetRowState state, EditBox idBox,
                                                        int startX, int y, int index, VoicePresetSpec.Mode mode,
                                                        String label, String tooltipKey) {
        FlatColorButton button = new FlatColorButton(
                startX + index * (MODE_BUTTON_WIDTH + MODE_BUTTON_GAP), y,
                MODE_BUTTON_WIDTH, 18, Component.literal(label), pressed -> {
            state.syncVisibleValue(idBox.getValue());
            state.setMode(mode);
            state.applyToBox(idBox);
            Minecraft.getInstance().setScreen((TTSSiteEditorScreen) (Object) this);
        });
        button.setSelect(state.mode() == mode);
        button.setTooltips(tooltipKey);
        ((TTSSiteEditorScreen) (Object) this).addRenderableWidget(button);
        return button;
    }

    @Unique
    private void touhouAIFun$addInstructionButton(VoicePresetRowState state, EditBox idBox,
                                                     int startX, int y, int index) {
        FlatColorButton button = new FlatColorButton(
                startX + index * (MODE_BUTTON_WIDTH + MODE_BUTTON_GAP), y,
                MODE_BUTTON_WIDTH, 18, Component.literal("词"), pressed -> {
            state.syncVisibleValue(idBox.getValue());
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(new TTSInstructionScreen(
                    (TTSSiteEditorScreen) (Object) this,
                    state.instruction(),
                    state::setInstruction
            ));
        });
        button.setSelect(StringUtils.isNotBlank(state.instruction()));
        button.setTooltips("gui.touhou_aifun.tts_instruction.tooltip");
        ((TTSSiteEditorScreen) (Object) this).addRenderableWidget(button);
    }

}
