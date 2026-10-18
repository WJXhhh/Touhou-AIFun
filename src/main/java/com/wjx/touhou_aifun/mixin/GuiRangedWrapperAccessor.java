package com.wjx.touhou_aifun.mixin;

import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.RangedWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RangedWrapper.class, remap = false)
public interface GuiRangedWrapperAccessor {
    @Accessor("compose") IItemHandlerModifiable touhouAIFun$compose();
    @Accessor("minSlot") int touhouAIFun$minSlot();
}
