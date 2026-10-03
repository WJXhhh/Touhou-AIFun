package com.wjx.touhou_aifun.maid.gui;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.AbstractList;
import java.util.function.Supplier;

/** Live list backing also covers mods that access Inventory.items directly. */
public final class MaidGuiInventoryBinding {
    private static final ThreadLocal<EntityMaid> CONSTRUCTING = new ThreadLocal<>();
    private MaidGuiInventoryBinding() { }
    public static EntityMaid constructing() { return CONSTRUCTING.get(); }
    public static <T> T construct(EntityMaid maid, Supplier<T> factory) {
        CONSTRUCTING.set(maid);
        try { return factory.get(); } finally { CONSTRUCTING.remove(); }
    }
    public static boolean enabled(Inventory inventory, int slot) {
        if (!(inventory.player instanceof MaidGuiActor actor)) return true;
        return slot == 0 || slot == 40 || slot > 0 && slot < 36
                && slot - 1 < actor.maid().getAvailableBackpackInv().getSlots();
    }
    public static NonNullList<ItemStack> items(EntityMaid maid) { return new LinkedStacks(maid, false); }
    public static NonNullList<ItemStack> offhand(EntityMaid maid) { return new LinkedStacks(maid, true); }
    public static int giveBack(EntityMaid maid, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack, false);
        if (!remainder.isEmpty()) {
            maid.spawnAtLocation(remainder);
        }
        return remainder.getCount();
    }
    private static final class LinkedStacks extends NonNullList<ItemStack> {
        LinkedStacks(EntityMaid maid, boolean offhand) {
            super(new AbstractList<>() {
                @Override public int size() { return offhand ? 1 : 36; }
                @Override public ItemStack get(int slot) {
                    if (offhand) return maid.getOffhandItem();
                    if (slot == 0) return maid.getMainHandItem();
                    return slot - 1 < maid.getAvailableBackpackInv().getSlots()
                            ? maid.getMaidInv().getStackInSlot(slot - 1) : ItemStack.EMPTY;
                }
                @Override public ItemStack set(int slot, ItemStack stack) {
                    ItemStack previous = get(slot);
                    if (offhand) maid.setItemInHand(InteractionHand.OFF_HAND, stack);
                    else if (slot == 0) maid.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    else if (slot - 1 < maid.getAvailableBackpackInv().getSlots()) {
                        maid.getMaidInv().setStackInSlot(slot - 1, stack);
                    } else if (!stack.isEmpty()) {
                        throw new IllegalArgumentException("unavailable_inventory_slot");
                    }
                    return previous;
                }
            }, ItemStack.EMPTY);
        }
    }
}
