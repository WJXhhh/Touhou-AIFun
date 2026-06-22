package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.github.tartaricacid.touhoulittlemaid.util.Rectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = AIChatSettingsHubScreen.class, remap = false)
public interface AIChatSettingsHubAccessor {
    @Accessor("state")
    AIChatSettingsHubScreen.SharedState touhouStepFun$getState();

    @Accessor("listArea")
    Rectangle touhouStepFun$getListArea();

    @Accessor("insufficientPermissions")
    boolean touhouStepFun$hasInsufficientPermissions();

    @Invoker("getContentX")
    int touhouStepFun$invokeGetContentX();

    @Invoker("getContentWidth")
    int touhouStepFun$invokeGetContentWidth();
}
