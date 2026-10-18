package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import java.util.List;
import java.util.Set;

/** Capacity and slot permission are separate facts; empty slots need not accept an item. */
final class MaidBackpackSpace {
    private MaidBackpackSpace() { }
    static JsonObject describe(IItemHandler handler,List<ItemStack> inventory,ItemStack item,int requested) {
        int empty=0,projectedEmpty=0,eligible=0,capacity=0;
        for(int slot=0;slot<inventory.size();slot++) {
            ItemStack stored=inventory.get(slot);
            if(handler.getStackInSlot(slot).isEmpty())empty++;
            if(stored.isEmpty())projectedEmpty++;
            if(!handler.isItemValid(slot,item) || handler.getSlotLimit(slot)<=0)continue;
            eligible++;
            if(stored.isEmpty() || ItemStack.isSameItemSameTags(stored,item))
                capacity+=Math.max(0,Math.min(handler.getSlotLimit(slot),item.getMaxStackSize())-stored.getCount());
        }
        String state=inventory.isEmpty()?"unavailable":eligible==0?"item_rejected":capacity>=requested?"available":projectedEmpty==0 && capacity==0?"full":"insufficient_capacity";
        var result=new JsonObject();result.addProperty("state",state);result.addProperty("item",MaidGuiSession.itemId(item));
        result.addProperty("requested_count",requested);result.addProperty("unlocked_slots",inventory.size());
        result.addProperty("empty_slots",empty);result.addProperty("projected_empty_slots",projectedEmpty);result.addProperty("eligible_slots",eligible);result.addProperty("capacity_for_item",capacity);
        result.addProperty("capacity_scope","planned_inventory");
        if(state.equals("item_rejected"))result.addProperty("reason",item.getItem().canFitInsideContainerItems()?"slot_rule_rejected":"container_item_disallowed");
        return result;
    }
    static JsonObject describe(IItemHandler handler,ItemStack item,int requested) {
        var inventory=new java.util.ArrayList<ItemStack>();
        for(int i=0;i<handler.getSlots();i++)inventory.add(handler.getStackInSlot(i));
        var result=describe(handler,inventory,item,requested);result.addProperty("capacity_scope","current_inventory");return result;
    }
    static JsonObject failure(JsonObject storage,String fullError,String rejectedError) {
        String state=storage.get("state").getAsString();
        if(state.equals("available")){state="insertion_denied";storage.addProperty("state",state);storage.addProperty("reason","insertion_simulation_rejected");}
        var result=MaidGuiSessionManager.error(Set.of("item_rejected","insertion_denied").contains(state)?rejectedError:state.equals("unavailable")?"maid_backpack_unavailable":fullError);
        result.add("backpack_state",storage);
        boolean blocked=Set.of("item_rejected","insertion_denied","unavailable").contains(state);result.addProperty("retryable",!blocked);
        result.addProperty("hint",blocked?"Unlocked maid storage rejects this item or is unavailable. Empty slots do not override slot rules. Do not retry unchanged, discard items, or change server rules automatically.":"Insufficient capacity for this item and quantity. Preserve settled receipts; free eligible space before retrying.");
        return result;
    }
}
