package com.wjx.touhou_aifun.mixin;

import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CombinedInvWrapper.class, remap = false)
public interface GuiCombinedWrapperAccessor {
    @Accessor("itemHandler") IItemHandlerModifiable[] touhouAIFun$handlers();
}
