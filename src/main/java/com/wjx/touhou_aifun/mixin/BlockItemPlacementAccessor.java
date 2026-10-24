package com.wjx.touhou_aifun.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Placement prediction must use the item's own placement state, not the expected block's state:
 * variant items such as signs, torches, skulls and corals select a standing or wall block based on
 * the player's view, so predicting only the expected block can choose a rotation that actually
 * places a different variant.
 */
@Mixin(BlockItem.class)
public interface BlockItemPlacementAccessor {
    @Invoker("getPlacementState")
    BlockState touhouAIFun$getPlacementState(BlockPlaceContext context);
}
