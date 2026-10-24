package com.wjx.touhou_aifun.mixin;

import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Placement validation needs both variants of signs/torches/skulls/corals. */
@Mixin(StandingAndWallBlockItem.class)
public interface StandingAndWallBlockItemAccessor {
    @Accessor("wallBlock")
    Block touhouAIFun$wallBlock();
}
