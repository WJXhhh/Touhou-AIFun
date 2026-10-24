package com.wjx.touhou_aifun.maid.action.block;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.gui.MaidGuiInventoryBinding;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

import javax.annotation.Nullable;

/**
 * Borrows an item from the maid into a hand for one simulated action and always restores the
 * remaining/transformed stack. All mutations must happen on the server thread.
 */
public final class MaidItemSelection {
    public static final int MAIN_SLOT = -1;
    public static final int OFFHAND_SLOT = -2;

    private MaidItemSelection() {
    }

    public record Source(ItemStack stack, int slot, InteractionHand hand) {
        public boolean mainHand() {
            return slot == MAIN_SLOT;
        }

        public boolean offhand() {
            return slot == OFFHAND_SLOT;
        }
    }

    public static final class Borrow {
        private final boolean borrowed;
        private final int slot;
        private final ItemStack originalMain;
        private final InteractionHand hand;
        private final ItemStack sourceBefore;

        private Borrow(boolean borrowed, int slot, ItemStack originalMain, InteractionHand hand, ItemStack sourceBefore) {
            this.borrowed = borrowed;
            this.slot = slot;
            this.originalMain = originalMain;
            this.hand = hand;
            this.sourceBefore = sourceBefore;
        }

        public boolean borrowed() {
            return borrowed;
        }
    }

    @Nullable
    public static Source findItem(EntityMaid maid, String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) return null;
        if (matches(maid.getMainHandItem(), key)) return new Source(maid.getMainHandItem(), MAIN_SLOT, InteractionHand.MAIN_HAND);
        if (matches(maid.getOffhandItem(), key)) return new Source(maid.getOffhandItem(), OFFHAND_SLOT, InteractionHand.OFF_HAND);
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack stack = backpack.getStackInSlot(i);
            if (matches(stack, key)) return new Source(stack, i, InteractionHand.MAIN_HAND);
        }
        return null;
    }

    @Nullable
    public static Source findTool(EntityMaid maid, java.util.function.Predicate<ItemStack> filter) {
        if (filter.test(maid.getMainHandItem())) return new Source(maid.getMainHandItem(), MAIN_SLOT, InteractionHand.MAIN_HAND);
        if (filter.test(maid.getOffhandItem())) return new Source(maid.getOffhandItem(), OFFHAND_SLOT, InteractionHand.OFF_HAND);
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack stack = backpack.getStackInSlot(i);
            if (filter.test(stack)) return new Source(stack, i, InteractionHand.MAIN_HAND);
        }
        return null;
    }

    public static int count(EntityMaid maid, String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) return 0;
        int count = countStack(maid.getMainHandItem(), key) + countStack(maid.getOffhandItem(), key);
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) count += countStack(backpack.getStackInSlot(i), key);
        return count;
    }

    public static int countAny(EntityMaid maid, java.util.function.Predicate<ItemStack> filter) {
        int count = filter.test(maid.getMainHandItem()) ? maid.getMainHandItem().getCount() : 0;
        count += filter.test(maid.getOffhandItem()) ? maid.getOffhandItem().getCount() : 0;
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack stack = backpack.getStackInSlot(i);
            if (filter.test(stack)) count += stack.getCount();
        }
        return count;
    }

    /** Moves one matching item from the backpack into the main hand. Hands are used directly. */
    @Nullable
    public static Borrow borrow(EntityMaid maid, Source source) {
        if (source == null) return null;
        if (source.mainHand() || source.offhand()) {
            return new Borrow(false, source.slot(), ItemStack.EMPTY, source.hand(), source.stack().copy());
        }
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        ItemStack extracted = backpack.extractItem(source.slot(), 1, false);
        if (extracted.isEmpty()) return null;
        ItemStack originalMain = maid.getMainHandItem().copy();
        maid.setItemInHand(InteractionHand.MAIN_HAND, extracted);
        return new Borrow(true, source.slot(), originalMain, InteractionHand.MAIN_HAND, extracted.copy());
    }

    /**
     * Restores the original hand and returns any unconsumed/transformed item to the maid.
     * The contract is deliberately conservative: a borrowed stack is never deleted.
     */
    public static void restore(EntityMaid maid, @Nullable Borrow borrow) {
        if (borrow == null) return;
        if (!borrow.borrowed) {
            // A direct-hand action may have consumed/transformed the stack in place; nothing to do.
            return;
        }
        ItemStack hand = maid.getMainHandItem().copy();
        maid.setItemInHand(InteractionHand.MAIN_HAND, borrow.originalMain);
        if (hand.isEmpty()) return;
        if (ItemStack.isSameItemSameTags(hand, borrow.sourceBefore)) {
            // Put the remainder back into the emptied source slot first, then anywhere.
            ItemStack leftover = insertBackpack(maid, borrow.slot, hand);
            if (!leftover.isEmpty()) giveBack(maid, leftover);
        } else {
            // Transformed result (bucket, bottle, ...) belongs in the maid inventory.
            giveBack(maid, hand);
        }
    }

    public static void shrinkHand(EntityMaid maid, InteractionHand hand, int amount) {
        ItemStack stack = maid.getItemInHand(hand);
        if (!stack.isEmpty()) stack.shrink(Math.max(0, amount));
    }

    public static ItemStack insertBackpack(EntityMaid maid, int preferredSlot, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        if (preferredSlot >= 0 && preferredSlot < backpack.getSlots()) {
            ItemStack remainder = backpack.insertItem(preferredSlot, stack, false);
            if (remainder.isEmpty()) return ItemStack.EMPTY;
            stack = remainder;
        }
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(backpack, stack, false);
        return remainder;
    }

    public static void giveBack(EntityMaid maid, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack, false);
        if (!remainder.isEmpty()) maid.spawnAtLocation(remainder);
    }

    public static void damageTool(EntityMaid maid, Source tool) {
        if (tool == null) return;
        if (tool.mainHand()) {
            tool.stack().hurtAndBreak(1, maid, ignored -> {
            });
            return;
        }
        if (tool.offhand()) {
            maid.getOffhandItem().hurtAndBreak(1, maid, ignored -> {
            });
            return;
        }
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        ItemStack extracted = backpack.extractItem(tool.slot(), 1, false);
        if (extracted.isEmpty()) return;
        extracted.hurtAndBreak(1, maid, ignored -> {
        });
        if (!extracted.isEmpty()) {
            ItemStack remainder = insertBackpack(maid, tool.slot(), extracted);
            if (!remainder.isEmpty()) giveBack(maid, remainder);
        }
    }

    public static boolean isItem(ItemStack stack, String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        return key != null && matches(stack, key);
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    public static String displayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return stack.getHoverName().getString();
    }

    public static boolean emptyHand(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.is(Items.AIR);
    }

    private static boolean matches(ItemStack stack, ResourceLocation key) {
        return !emptyHand(stack) && key.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static int countStack(ItemStack stack, ResourceLocation key) {
        return matches(stack, key) ? stack.getCount() : 0;
    }

    /** Used by the build queue to inspect the maid's available inventory without moving it. */
    public static int availableSlots(EntityMaid maid) {
        IItemHandler handler = maid.getAvailableBackpackInv();
        int empty = 0;
        for (int i = 0; i < handler.getSlots(); i++) if (handler.getStackInSlot(i).isEmpty()) empty++;
        return empty;
    }

    /** Finds the wrapper slot used by TLM's edible-block placeAsFood hook. */
    public static int findAvailableInvSlot(EntityMaid maid, String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) return -1;
        IItemHandler handler = maid.getAvailableInv(true);
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (matches(stack, key)) return i;
        }
        return -1;
    }
}
