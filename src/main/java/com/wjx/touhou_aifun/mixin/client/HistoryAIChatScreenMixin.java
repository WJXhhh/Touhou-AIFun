package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.HistoryAIChatScreen;
import com.wjx.touhou_aifun.client.gui.HistoryScrollbar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = HistoryAIChatScreen.class, remap = false)
public abstract class HistoryAIChatScreenMixin extends Screen {
    @Shadow private double scroll;
    @Shadow private int maxHeight;
    @Shadow private int posX;
    @Shadow private int historyTop;
    @Shadow private int historyBottom;

    protected HistoryAIChatScreenMixin(Component title) {
        super(title);
    }

    // These are vanilla Screen overrides, so their selectors must be remapped for production.
    @Inject(method = "init", at = @At("TAIL"), remap = true)
    private void touhouAIFun$initHistoryScroll(CallbackInfo ci) {
        scroll = Math.min(0, historyBottom - maxHeight);
        int visibleHeight = historyBottom - historyTop;
        if (visibleHeight > 0) {
            addRenderableWidget(new HistoryScrollbar(posX + 130, historyTop, visibleHeight,
                    Math.max(0, maxHeight - historyTop), () -> scroll, value -> scroll = value));
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$scrollHistory(double mouseX, double mouseY, double delta,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (mouseX >= posX - 128 && mouseX < posX + 136
                && mouseY >= historyTop && mouseY < historyBottom) {
            scroll = Mth.clamp(scroll + delta * 15, Math.min(0, historyBottom - maxHeight), 0);
            cir.setReturnValue(true);
        } else {
            // The base implementation scrolls history even when hovering over the summary/buttons.
            cir.setReturnValue(super.mouseScrolled(mouseX, mouseY, delta));
        }
    }
}
