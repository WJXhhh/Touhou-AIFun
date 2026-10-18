package com.wjx.touhou_aifun.maid.gui;

import com.wjx.touhou_aifun.mixin.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.items.*;
import net.minecraftforge.items.wrapper.*;

/** Resolve Forge inventory wrappers by identity, never by matching item contents or GUI position. */
public final class MaidGuiSlots {
    private record Address(Inventory inventory, int index) { }
    private MaidGuiSlots() { }
    private static Address address(IItemHandler handler, int index, int depth) {
        if (depth > 8 || index < 0 || index >= handler.getSlots()) return null;
        if (handler instanceof InvWrapper wrapper)
            return wrapper.getInv() instanceof Inventory inventory ? new Address(inventory, index) : null;
        if (handler instanceof RangedWrapper && handler instanceof GuiRangedWrapperAccessor range)
            return address(range.touhouAIFun$compose(), index + range.touhouAIFun$minSlot(), depth + 1);
        if (handler instanceof CombinedInvWrapper && handler instanceof GuiCombinedWrapperAccessor combined) {
            for (IItemHandler child : combined.touhouAIFun$handlers()) {
                if (index < child.getSlots()) return address(child, index, depth + 1);
                index -= child.getSlots();
            }
        }
        return null;
    }
    private static Address address(Slot slot) {
        if (slot.container instanceof Inventory inventory) return new Address(inventory, slot.getContainerSlot());
        return slot instanceof SlotItemHandler forge ? address(forge.getItemHandler(), slot.getContainerSlot(), 0) : null;
    }
    public static int inventorySlot(Slot slot, Inventory inventory) {
        Address address = address(slot);
        return address != null && address.inventory == inventory ? address.index : -1;
    }
    public static boolean belongsTo(Slot slot, Inventory inventory) { return inventorySlot(slot, inventory) >= 0; }
    public static boolean enabled(Slot slot) {
        Address address = address(slot);
        return address == null || MaidGuiInventoryBinding.enabled(address.inventory, address.index);
    }
    public static Object storage(Slot slot) { return slot instanceof SlotItemHandler forge ? forge.getItemHandler() : slot.container; }
}
