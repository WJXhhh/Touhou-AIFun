package com.wjx.touhou_aifun.maid.action.block;

import com.github.tartaricacid.touhoulittlemaid.api.block.IMaidEdibleBlock;
import com.github.tartaricacid.touhoulittlemaid.block.BlockSnackCabinet;
import com.github.tartaricacid.touhoulittlemaid.datagen.tag.TagBlock;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.maid.gui.MaidGuiActor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Simulated block interactions. Never uses a direct setBlock to place an item. */
public final class MaidBlockActions {
    private MaidBlockActions() {
    }

    public record Outcome(boolean success, String error, String detail, BlockState previous, BlockState actual,
                          InteractionResult interactionResult, boolean menuOpened, int itemConsumed,
                          List<MaidBlockActionResult.Drop> drops, @Nullable Integer snackCabinetType,
                          @Nullable MaidPlacementOrientation.Result orientation) {
        static Outcome failure(String error, String detail, BlockState previous, BlockState actual) {
            return new Outcome(false, error, detail, previous, actual, null, false, 0, List.of(), null, null);
        }
    }

    public static Outcome place(EntityMaid maid, MaidGuiActor actor, MaidBlockActionRequest request,
                                BlockPos target, BlockPos support, Direction face, BlockHitResult hit,
                                ItemStack handStack, InteractionHand hand, @Nullable MaidItemSelection.Borrow borrow,
                                boolean useHands) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return Outcome.failure("wrong_thread", "The maid is not in a server level.", null, null);
        }
        BlockState previous = level.getBlockState(target);
        MaidBlockStateMatcher.Expected expected = expectedFor(request, handStack);
        if (MaidBlockStateMatcher.matches(previous, expected, request.strict())) {
            return new Outcome(true, "", "The target already matches the expected block.", previous, previous,
                    null, false, 0, List.of(), snackCabinetType(level, support), null);
        }
        if (!previous.canBeReplaced()) {
            return Outcome.failure("target_occupied", "The target position is not replaceable.", previous, previous);
        }
        BlockState supportState = level.getBlockState(support);
        int edibleSlot = -1;
        IMaidEdibleBlock edible = null;
        // TLM's placeAsFood extracts from the maid inventory itself. For a borrowed backpack item
        // the generic simulated right-click below is safer; it consumes the item already in hand.
        if ((borrow == null || !borrow.borrowed()) && !handStack.isEmpty()
                && supportState.is(TagBlock.MAID_SNACK_STAND_BLOCK)) {
            int slot = MaidItemSelection.findAvailableInvSlot(maid, MaidItemSelection.itemId(handStack));
            if (slot >= 0) {
                for (IMaidEdibleBlock candidate : MaidEdibleBlockManager.getEdibleBlocks()) {
                    try {
                        if (!candidate.canPlaceAsFood(maid, handStack, slot)) continue;
                        if (!candidate.shouldPlaceTo(maid, target, previous, handStack)) continue;
                    } catch (RuntimeException rejected) {
                        TouhouAIFun.LOGGER.warn("Edible-block provider {} rejected placement at {}",
                                candidate.getClass().getName(), target, rejected);
                        continue;
                    }
                    edible = candidate;
                    edibleSlot = slot;
                    break;
                }
            }
        }
        if (edible != null) {
            int before = handStack.getCount();
            boolean placed;
            try {
                placed = edible.placeAsFood(maid, target, handStack, edibleSlot);
            } catch (RuntimeException failed) {
                TouhouAIFun.LOGGER.warn("Edible-block placement failed at {}", target, failed);
                placed = false;
            }
            BlockState actual = level.getBlockState(target);
            if (!placed && !MaidBlockStateMatcher.matches(actual, expected, request.strict())) {
                // TLM's hook extracts one item before placing. If the world did not change, put the
                // extracted item back so a failed special placement cannot silently delete it.
                restoreExtracted(maid, handStack, before, edibleSlot);
                return Outcome.failure("placement_failed", "The edible-block provider refused the placement.",
                        previous, actual);
            }
            return new Outcome(true, "", "Placed with TLM's edible-block hook.", previous, actual, null,
                    false, Math.max(0, before - handStack.getCount()), List.of(),
                    snackCabinetType(level, support), null);
        }
        if (handStack.isEmpty() && useHands) {
            return Outcome.failure("missing_item", "No placeable item is held or available.", previous, previous);
        }
        // Clicking an existing slab/snow layer/candle with a matching item would redirect the
        // placement into that support (double slab, taller snow) and mutate an unrelated block.
        try {
            if (level.getBlockState(support).canBeReplaced(
                    new net.minecraft.world.item.context.BlockPlaceContext(actor, hand, handStack, hit))) {
                return Outcome.failure("placement_redirected",
                        "Vanilla would replace the support block instead of placing at the target.",
                        previous, level.getBlockState(target));
            }
        } catch (RuntimeException ignored) {
        }
        actor.align();
        MaidPlacementOrientation.Result orientation = MaidPlacementOrientation.resolve(
                actor, hand, handStack, hit, expected, request.facing());
        if (MaidPlacementOrientation.requiresVerifiedOrientation(handStack, expected.block())
                && !orientation.satisfied()) {
            return new Outcome(false, "orientation_unreachable",
                    "No placement rotation can produce the expected wall variant at this target.",
                    previous, level.getBlockState(target), null, false, 0, List.of(),
                    snackCabinetType(level, support), orientation);
        }
        actor.orient(orientation.yaw(), orientation.pitch());
        int before = handStack.getCount();
        InteractionResult result;
        try {
            result = actor.gameMode.useItemOn(actor, level, handStack, hand, hit);
        } catch (RuntimeException failed) {
            TouhouAIFun.LOGGER.warn("Simulated placement failed at {}", target, failed);
            return Outcome.failure("placement_failed", "The simulated right-click failed.", previous,
                    level.getBlockState(target));
        }
        boolean menuOpened = actor.containerMenu != actor.inventoryMenu;
        if (menuOpened) {
            try {
                actor.closeContainer();
            } catch (RuntimeException ignored) {
            }
            return new Outcome(false, "menu_opened", "The clicked block opened a menu; use open_gui instead.",
                    previous, level.getBlockState(target), result, true, 0, List.of(),
                    snackCabinetType(level, support), orientation);
        }
        BlockState actual = level.getBlockState(target);
        TouhouAIFun.LOGGER.debug("AIFun placement: target={} expected={} actual={} face={} yaw={} pitch={} source={} satisfied={} predicted={}",
                target, MaidBlockStateMatcher.serialize(expected), MaidBlockStateMatcher.serialize(actual),
                hit.getDirection(), orientation.yaw(), orientation.pitch(), orientation.source(),
                orientation.satisfied(), orientation.predicted());
        int consumed = Math.max(0, before - actor.getItemInHand(hand).getCount());
        if (consumed == 0 && !handStack.isEmpty() && !actual.is(previous.getBlock())
                && MaidBlockStateMatcher.matches(actual, expected, request.strict())) {
            // Some fake-player/game-mode combinations do not shrink the stack even though the block
            // was placed. Reconcile exactly once so the maid never duplicates the item.
            MaidItemSelection.shrinkHand(maid, hand, 1);
            consumed = 1;
        }
        return new Outcome(true, "", "", previous, actual, result, false, consumed, List.of(),
                snackCabinetType(level, support), orientation);
    }

    public static Outcome interact(EntityMaid maid, MaidGuiActor actor, BlockHitResult hit,
                                   ItemStack handStack, InteractionHand hand) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return Outcome.failure("wrong_thread", "The maid is not in a server level.", null, null);
        }
        BlockState previous = level.getBlockState(hit.getBlockPos());
        actor.align();
        int before = handStack.getCount();
        InteractionResult result;
        try {
            result = actor.gameMode.useItemOn(actor, level, handStack, hand, hit);
        } catch (RuntimeException failed) {
            TouhouAIFun.LOGGER.warn("Simulated block interaction failed at {}", hit.getBlockPos(), failed);
            return Outcome.failure("interaction_failed", "The simulated right-click failed.", previous,
                    level.getBlockState(hit.getBlockPos()));
        }
        boolean menuOpened = actor.containerMenu != actor.inventoryMenu;
        if (menuOpened) {
            try {
                actor.closeContainer();
            } catch (RuntimeException ignored) {
            }
        }
        BlockState actual = level.getBlockState(hit.getBlockPos());
        int consumed = Math.max(0, before - handStack.getCount());
        return new Outcome(!menuOpened, menuOpened ? "menu_opened" : "",
                menuOpened ? "The interaction opened a menu; use open_gui instead." : "",
                previous, actual, result, menuOpened, consumed, List.of(), null, null);
    }

    public static Outcome mine(EntityMaid maid, MaidGuiActor actor, BlockPos target, @Nullable ItemStack toolStack) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return Outcome.failure("wrong_thread", "The maid is not in a server level.", null, null);
        }
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return new Outcome(true, "", "The target is already air.", state, state, null, false, 0,
                    List.of(), null, null);
        }
        if (state.getDestroySpeed(level, target) < 0) {
            return Outcome.failure("unbreakable", "The target block cannot be destroyed.", state, state);
        }
        if (!maid.canDestroyBlock(target)) {
            return Outcome.failure("protected", "The maid is not allowed to destroy this block.", state, state);
        }
        ResourceLocation dropKey = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem());
        int before = dropKey == null ? 0 : MaidItemSelection.count(maid, dropKey.toString());
        level.levelEvent(2001, target, Block.getId(state));
        boolean removed;
        try {
            state.getBlock().playerWillDestroy(level, target, state, actor);
            BlockState current = level.getBlockState(target);
            BlockEntity blockEntity = current.hasBlockEntity() ? level.getBlockEntity(target) : null;
            if (!current.isAir()) {
                maid.dropResourcesToMaidInv(current, level, target, blockEntity, maid,
                        toolStack == null ? ItemStack.EMPTY : toolStack);
            }
            removed = level.setBlock(target, current.getFluidState().createLegacyBlock(), 3);
            if (removed) level.gameEvent(actor, GameEvent.BLOCK_DESTROY, target);
        } catch (RuntimeException failed) {
            TouhouAIFun.LOGGER.warn("Simulated block break failed at {}", target, failed);
            return Outcome.failure("break_failed", "The simulated break failed.", state, level.getBlockState(target));
        }
        BlockState actual = level.getBlockState(target);
        List<MaidBlockActionResult.Drop> drops = new ArrayList<>();
        if (dropKey != null) {
            int after = MaidItemSelection.count(maid, dropKey.toString());
            if (after > before) drops.add(new MaidBlockActionResult.Drop(dropKey.toString(), after - before));
        }
        return new Outcome(removed, "", "", state, actual, null, false, 0, drops, null, null);
    }

    /**
     * Expected state for verification. An explicit {@code facing} request is merged into the
     * expected state as the block's {@code facing} (or {@code axis}) property when the block owns
     * that property, so the result actually proves the requested orientation instead of trusting
     * the placement attempt. All other requested properties are preserved.
     */
    public static MaidBlockStateMatcher.Expected expectedFor(MaidBlockActionRequest request, ItemStack handStack) {
        MaidBlockStateMatcher.Expected base;
        if (request.expected() != null) {
            base = MaidBlockStateMatcher.parse(request.expected());
        } else if (!handStack.isEmpty() && handStack.getItem() instanceof BlockItem item) {
            base = new MaidBlockStateMatcher.Expected(item.getBlock(), false, java.util.Map.of());
        } else {
            base = MaidBlockStateMatcher.air();
        }
        return applyFacing(base, request.facing());
    }

    private static MaidBlockStateMatcher.Expected applyFacing(MaidBlockStateMatcher.Expected base,
                                                              @Nullable Direction facing) {
        if (base == null || base.air() || facing == null) return base;
        java.util.Map<String, String> properties = new java.util.LinkedHashMap<>(base.properties());
        net.minecraft.world.level.block.state.properties.Property<?> facingProperty =
                base.block().getStateDefinition().getProperty("facing");
        if (facingProperty != null) {
            String value = facing.getName();
            if (facingProperty.getValue(value).isEmpty()) throw new IllegalArgumentException("invalid_facing_for_block");
            String existing = properties.get("facing");
            if (existing != null && !existing.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("facing_conflict");
            }
            properties.put("facing", value);
            return new MaidBlockStateMatcher.Expected(base.block(), false, properties);
        }
        net.minecraft.world.level.block.state.properties.Property<?> axisProperty =
                base.block().getStateDefinition().getProperty("axis");
        if (axisProperty != null) {
            String value = switch (facing.getAxis()) {
                case X -> "x";
                case Y -> "y";
                case Z -> "z";
            };
            if (axisProperty.getValue(value).isEmpty()) throw new IllegalArgumentException("invalid_facing_for_block");
            String existing = properties.get("axis");
            if (existing != null && !existing.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("facing_conflict");
            }
            properties.put("axis", value);
            return new MaidBlockStateMatcher.Expected(base.block(), false, properties);
        }
        // A facing request without a facing/axis state property cannot be verified; silently
        // ignoring it would report a satisfied orientation that was never applied.
        throw new IllegalArgumentException("facing_not_supported");
    }

    @Nullable
    private static Integer snackCabinetType(ServerLevel level, BlockPos support) {
        BlockState state = level.getBlockState(support);
        if (!(state.getBlock() instanceof BlockSnackCabinet)) return null;
        try {
            return state.getValue(BlockSnackCabinet.TYPE);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static void restoreExtracted(EntityMaid maid, ItemStack handStack, int before, int edibleSlot) {
        if (handStack.getCount() >= before || before <= 0) return;
        ItemStack restore = handStack.copy();
        restore.setCount(1);
        MaidItemSelection.giveBack(maid, restore);
    }

    public static boolean isAir(BlockState state) {
        return state != null && state.is(Blocks.AIR);
    }
}
