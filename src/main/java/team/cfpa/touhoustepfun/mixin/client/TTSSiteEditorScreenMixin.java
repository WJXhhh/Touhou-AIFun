package team.cfpa.touhoustepfun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.TTSSiteEditorScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.util.Rectangle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
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
import team.cfpa.touhoustepfun.client.gui.CustomVoiceScreen;
import team.cfpa.touhoustepfun.client.gui.TTSInstructionScreen;
import team.cfpa.touhoustepfun.client.gui.VoicePresetRowState;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetCapabilities;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;

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
    private final Map<Object, VoicePresetRowState> touhouStepFun$rowStates = new WeakHashMap<>();

    @ModifyConstant(method = "init", constant = @Constant(intValue = 400), remap = true)
    private int touhouStepFun$editorWidthInInit(int original) {
        return this.touhouStepFun$getEditorWidth();
    }

    @ModifyConstant(method = "init", constant = @Constant(intValue = 376), remap = true)
    private int touhouStepFun$contentWidthInInit(int original) {
        return this.touhouStepFun$getEditorWidth() - 24;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 400), remap = true)
    private int touhouStepFun$editorWidthInRender(int original) {
        return this.touhouStepFun$getEditorWidth();
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 200), remap = true)
    private int touhouStepFun$editorCenterInRender(int original) {
        return this.touhouStepFun$getEditorWidth() / 2;
    }

    @Unique
    private int touhouStepFun$getEditorWidth() {
        TTSSiteEditorScreen screen = (TTSSiteEditorScreen) (Object) this;
        return Math.max(1, Math.min(MAX_EDITOR_WIDTH, screen.width - 32));
    }

    @Inject(method = "createModelRows", at = @At("TAIL"))
    private void touhouStepFun$addVoiceModeButtons(int left, int contentWidth, CallbackInfo ci) {
        if (!(this.layout instanceof VoicePresetCapabilities capabilities)) {
            return;
        }
        int visibleCount = Math.max(1, (int) ((this.modelArea.h - 4) / 22));
        int endIndex = Math.min(this.modelRows.size(), this.modelScrollOffset + visibleCount);
        for (int rowIndex = this.modelScrollOffset; rowIndex < endIndex; rowIndex++) {
            Object row = this.modelRows.get(rowIndex);
            ModelRowAccessor accessor = (ModelRowAccessor) row;
            EditBox idBox = accessor.touhouStepFun$getIdBox();
            EditBox nameBox = accessor.touhouStepFun$getNameBox();
            if (idBox == null || nameBox == null) {
                continue;
            }

            VoicePresetRowState state = this.touhouStepFun$rowStates.get(row);
            if (state == null) {
                state = VoicePresetRowState.fromStoredValue(idBox.getValue());
                this.touhouStepFun$rowStates.put(row, state);
            } else {
                state.syncVisibleValue(idBox.getValue());
            }

            int buttonCount = 1 + (capabilities.supportsVoiceDesign() ? 1 : 0)
                    + (capabilities.supportsReferenceVoice() ? 1 : 0)
                    + (capabilities.supportsSynthesisInstruction() ? 1 : 0)
                    + (capabilities.supportsStreamingOutput() ? 1 : 0);
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

            int index = 0;
            this.touhouStepFun$addModeButton(row, state, idBox, buttonX, buttonY, index++,
                    VoicePresetSpec.Mode.DIRECT_ID, "ID", "gui.touhou_stepfun.voice_mode.id");
            if (capabilities.supportsVoiceDesign()) {
                this.touhouStepFun$addModeButton(row, state, idBox, buttonX, buttonY, index++,
                        VoicePresetSpec.Mode.VOICE_DESIGN, "设", "gui.touhou_stepfun.voice_mode.design");
            }
            if (capabilities.supportsReferenceVoice()) {
                this.touhouStepFun$addModeButton(row, state, idBox, buttonX, buttonY, index++,
                        VoicePresetSpec.Mode.REFERENCE_SAMPLE, "样", "gui.touhou_stepfun.voice_mode.reference");
            }
            if (capabilities.supportsSynthesisInstruction()) {
                this.touhouStepFun$addInstructionButton(state, idBox, buttonX, buttonY, index++);
            }
            if (capabilities.supportsStreamingOutput()) {
                this.touhouStepFun$addStreamingButton(state, idBox, buttonX, buttonY, index);
            }
        }
    }

    @Inject(method = "buildModels", at = @At("RETURN"), cancellable = true)
    private void touhouStepFun$encodeVoiceModes(CallbackInfoReturnable<Map<String, String>> cir) {
        if (!(this.layout instanceof VoicePresetCapabilities)) {
            return;
        }
        Map<String, String> models = new LinkedHashMap<>();
        for (Object row : this.modelRows) {
            ModelRowAccessor accessor = (ModelRowAccessor) row;
            EditBox idBox = accessor.touhouStepFun$getIdBox();
            EditBox nameBox = accessor.touhouStepFun$getNameBox();
            String visibleValue = StringUtils.trimToEmpty(idBox != null ? idBox.getValue() : accessor.touhouStepFun$getId());
            String name = StringUtils.trimToEmpty(nameBox != null ? nameBox.getValue() : accessor.touhouStepFun$getName());
            if (StringUtils.isBlank(visibleValue) || StringUtils.isBlank(name)) {
                continue;
            }
            VoicePresetRowState state = this.touhouStepFun$rowStates.computeIfAbsent(row,
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
    private void touhouStepFun$addModeButton(Object row, VoicePresetRowState state, EditBox idBox,
                                              int startX, int y, int index, VoicePresetSpec.Mode mode,
                                              String label, String tooltipKey) {
        FlatColorButton button = new FlatColorButton(
                startX + index * (MODE_BUTTON_WIDTH + MODE_BUTTON_GAP), y,
                MODE_BUTTON_WIDTH, 18, Component.literal(label), pressed -> {
            state.syncVisibleValue(idBox.getValue());
            state.setMode(mode);
            state.applyToBox(idBox);
            if (mode == VoicePresetSpec.Mode.REFERENCE_SAMPLE) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.setScreen(new CustomVoiceScreen(
                        (TTSSiteEditorScreen) (Object) this,
                        state.value(mode),
                        state.referenceText(),
                        (audioPath, referenceText) -> {
                            state.updateReference(audioPath, referenceText);
                            state.applyToBox(idBox);
                        }
                ));
            } else {
                Minecraft.getInstance().setScreen((TTSSiteEditorScreen) (Object) this);
            }
        });
        button.setSelect(state.mode() == mode);
        button.setTooltips(tooltipKey);
        ((TTSSiteEditorScreen) (Object) this).addRenderableWidget(button);
    }

    @Unique
    private void touhouStepFun$addStreamingButton(VoicePresetRowState state, EditBox idBox,
                                                   int startX, int y, int index) {
        String tooltipKey = state.streaming()
                ? "gui.touhou_stepfun.voice_stream.on" : "gui.touhou_stepfun.voice_stream.off";
        FlatColorButton button = new FlatColorButton(
                startX + index * (MODE_BUTTON_WIDTH + MODE_BUTTON_GAP), y,
                MODE_BUTTON_WIDTH, 18, Component.literal("流"), pressed -> {
            state.syncVisibleValue(idBox.getValue());
            state.toggleStreaming();
            Minecraft.getInstance().setScreen((TTSSiteEditorScreen) (Object) this);
        });
        button.setSelect(state.streaming());
        button.setTooltips(tooltipKey);
        ((TTSSiteEditorScreen) (Object) this).addRenderableWidget(button);
    }

    @Unique
    private void touhouStepFun$addInstructionButton(VoicePresetRowState state, EditBox idBox,
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
        button.setTooltips("gui.touhou_stepfun.tts_instruction.tooltip");
        ((TTSSiteEditorScreen) (Object) this).addRenderableWidget(button);
    }

}
