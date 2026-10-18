package com.wjx.touhou_aifun.maid.gui;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.wjx.touhou_aifun.mixin.GuiMenuDataAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import io.netty.buffer.Unpooled;

import java.util.*;

public final class MaidGuiSession {
    public final UUID id = UUID.randomUUID();
    public final GuiSnapshotProjection projection = new GuiSnapshotProjection();
    public final GuiTransferLedger transferLedger = new GuiTransferLedger();
    public final EntityMaid maid;
    public final Object callback;
    public final UUID owner;
    public final MaidGuiActor actor;
    public final GuiWaitBudget waitBudget;
    public final Map<String, JsonObject> results = new LinkedHashMap<>();
    public final Map<String, Integer> delivered = new HashMap<>();
    public final Map<Integer, ItemStack> observedSlots = new HashMap<>();
    public final Map<Integer, ItemStack> observedBackpack = new HashMap<>();
    public ItemStack observedCarried = ItemStack.EMPTY;
    public GuiWaitPolicy policy;
    public long lastTick;
    public long activeTicks;
    public int actionCount;
    public int borrowedItemSlot = -1;
    public int droppedCount;
    public String lastAction = "opened";
    public net.minecraft.core.BlockPos targetPosition;
    public long frame;
    public String clientLayout = "";
    public JsonArray clientControls = new JsonArray();
    public BitSet clientActiveSlots;
    public boolean clientControlsTruncated;
    public GuiProcessState process = GuiProcessState.unknown();
    public GuiWaitTracker waiting;
    public boolean startOnly;
    public long startConfirmationTick;
    public String startBlocked = "";
    public int startBlockedSamples;
    public java.util.concurrent.CompletableFuture<JsonObject> waitFuture;
    public GuiCraftingService.CraftJob crafting;
    public AbstractContainerMenu visuallyObservedMenu;
    public boolean unfamiliarMenu() {
        try {
            var type = BuiltInRegistries.MENU.getKey(menu().getType());
            return type != null && !type.getNamespace().equals("minecraft")
                    && MaidGuiAdapters.gui().stream().noneMatch(adapter -> adapter.supports(menu()));
        } catch (UnsupportedOperationException unavailable) { return true; }
    }
    public String goalItem = "";
    public int goalCount;
    public int goalOutputSlot = -1;
    private int lastOutput;
    private String lastOutputId = "";
    private long production;

    public MaidGuiSession(EntityMaid maid, Object callback, GuiWaitPolicy policy, GuiWaitBudget budget) {
        this.maid = maid; this.callback = callback; this.owner = maid.getOwnerUUID();
        this.policy = policy; this.waitBudget = budget;
        actor = MaidGuiInventoryBinding.construct(maid, () -> new MaidGuiActor(maid));
        lastTick = maid.level().getGameTime();
    }
    public AbstractContainerMenu menu() { return actor.containerMenu; }
    public long observeProduction(ItemStack output) {
        String item = itemId(output);
        if (item.equals(lastOutputId)) production += Math.max(0, output.getCount() - lastOutput);
        else production += output.getCount();
        lastOutputId = item; lastOutput = output.getCount();
        return production;
    }
    public static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
    public static JsonObject stack(ItemStack stack) {
        JsonObject value = new JsonObject();
        value.addProperty("item", itemId(stack)); value.addProperty("count", stack.getCount());
        value.addProperty("name", stack.isEmpty() ? "" : stack.getHoverName().getString());
        value.addProperty("fingerprint", Integer.toHexString(stack.save(new CompoundTag()).hashCode()));
        return value;
    }
    public JsonObject snapshot(String status) {
        return snapshot(status, true);
    }
    public JsonObject snapshot(String status, boolean remember) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", maid.level().dimension().location().toString());
        result.addProperty("status", status); result.addProperty("session_id", id.toString());
        result.addProperty("title", actor.title().getString());
        String menuName;
        try { menuName = BuiltInRegistries.MENU.getKey(menu().getType()).toString(); }
        catch (UnsupportedOperationException unavailable) { menuName = menu().getClass().getName(); }
        result.addProperty("menu", menuName);
        result.addProperty("wait_policy", policy.name()); result.addProperty("last_action", lastAction);
        if (targetPosition != null) {
            JsonArray position = new JsonArray(); position.add(targetPosition.getX()); position.add(targetPosition.getY()); position.add(targetPosition.getZ());
            result.add("target_position", position);
        }
        result.addProperty("frame_id", frame); result.addProperty("layout", clientLayout);
        result.addProperty("waited_ticks", waitBudget.consumed());
        result.addProperty("delivered_count", delivered.getOrDefault(goalItem, 0));
        result.addProperty("goal_count", goalCount); result.addProperty("goal_item", goalItem);
        result.addProperty("dropped_count", droppedCount + actor.droppedCount);
        if (menu() instanceof AnvilMenu anvil && anvil.getCost() > actor.experienceLevel)
            result.addProperty("unavailable_reason", "player_experience_not_bridged");
        result.add("process", new Gson().toJsonTree(process));
        JsonObject compatibility = new JsonObject(); compatibility.addProperty("slots", "standard_menu");
        compatibility.addProperty("rendering", frame > 0 ? "captured" : "requires_owner_client");
        compatibility.addProperty("buttons", "standard_or_native_forge_play_or_adapter");
        compatibility.addProperty("processing", MaidGuiAdapters.processSupported(menu()) ? "adapter" : "unknown");
        result.add("compatibility", compatibility);
        result.addProperty("observation_source", "authoritative_server_menu_and_maid_inventory");
        result.addProperty("visual_inspection_required", unfamiliarMenu() && visuallyObservedMenu != menu());
        if (unfamiliarMenu()) result.addProperty("operation_guidance", "Inspect this unfamiliar GUI with visual=true before moving items. Identify native controls and input/output slots from the image and slot permissions. Native Forge play packets are bridged as the maid. After actions verify current server slots and actual maid inventory; a button click or empty input never proves output delivery. Unknown progress remains unknown; use wait_gui with the observed output slot and exact item/count.");
        JsonArray slots = new JsonArray(); if (remember) observedSlots.clear();
        for (int i = 0; i < Math.min(512, menu().slots.size()); i++) {
            Slot slot = menu().getSlot(i); ItemStack contents = slot.getItem();
            if (remember) observedSlots.put(i, contents.copy());
            JsonObject value = stack(contents); value.addProperty("slot", i);
            value.addProperty("x", slot.x); value.addProperty("y", slot.y);
            if (frame > 0 && clientActiveSlots != null) value.addProperty("client_active", clientActiveSlots.get(i));
            value.addProperty("maid_inventory", MaidGuiSlots.belongsTo(slot, actor.getInventory()));
            if (MaidGuiSlots.belongsTo(slot, actor.getInventory())) {
                int inventorySlot = MaidGuiSlots.inventorySlot(slot, actor.getInventory());
                value.addProperty("inventory_slot", inventorySlot);
                value.addProperty("maid_storage", inventorySlot == 0 ? "main_hand" : inventorySlot == 40 ? "offhand"
                        : inventorySlot > 0 && inventorySlot < 36 && MaidGuiInventoryBinding.enabled(actor.getInventory(), inventorySlot) ? "backpack" : "unavailable");
                if (inventorySlot > 0 && inventorySlot < 36 && MaidGuiInventoryBinding.enabled(actor.getInventory(), inventorySlot))
                    value.addProperty("backpack_slot", inventorySlot - 1);
            }
            value.addProperty("enabled", MaidGuiSlots.enabled(slot));
            value.addProperty("may_take", slot.mayPickup(actor));
            if (unfamiliarMenu() && !MaidGuiSlots.belongsTo(slot, actor.getInventory())) {
                JsonArray accepted = new JsonArray(); Set<String> seen = new HashSet<>();
                var bag = maid.getAvailableBackpackInv();
                for (int n = 0; n < bag.getSlots() && accepted.size() < 8; n++) {
                    ItemStack candidate = bag.getStackInSlot(n);
                    if (!candidate.isEmpty() && seen.add(itemId(candidate)) && slot.mayPlace(candidate)) accepted.add(itemId(candidate));
                }
                value.add("accepted_backpack_items", accepted);
            }
            slots.add(value);
        }
        result.add("slots", slots); if (remember) observedCarried = menu().getCarried().copy();
        result.add("carried", stack(menu().getCarried()));
        JsonArray backpack = new JsonArray(); if (remember) observedBackpack.clear();
        for (int i = 0; i < maid.getAvailableBackpackInv().getSlots(); i++) {
            ItemStack contents = maid.getAvailableBackpackInv().getStackInSlot(i);
            JsonObject entry = stack(contents); entry.addProperty("backpack_slot", i); backpack.add(entry);
            if (remember) observedBackpack.put(i, contents.copy());
        }
        result.add("backpack_slots", backpack);
        JsonArray data = new JsonArray();
        for (DataSlot slot : ((GuiMenuDataAccessor) menu()).touhouAIFun$dataSlots()) data.add(slot.get());
        result.add("data", data);
        JsonArray controls = clientControls.deepCopy();
        if (menu() instanceof AnvilMenu) controls.add(control("rename", "text", "Rename item"));
        if (menu() instanceof MerchantMenu && actor.offers() != null) {
            for (int i = 0; i < actor.offers().size(); i++) {
                JsonObject trade = control("trade:" + i, "trade", actor.offers().get(i).getResult().getHoverName().getString());
                trade.add("cost_a", stack(actor.offers().get(i).getCostA()));
                trade.add("cost_b", stack(actor.offers().get(i).getCostB()));
                trade.add("result", stack(actor.offers().get(i).getResult())); controls.add(trade);
            }
        }
        for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(menu())) controls.addAll(adapter.controls(this));
        result.add("controls", controls);
        if (clientControlsTruncated) result.addProperty("controls_truncated", true);
        for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(menu())) {
            result.add("adapter_state", adapter.state(this)); break;
        }
        return result;
    }
    private static JsonObject control(String id, String kind, String label) {
        JsonObject value = new JsonObject(); value.addProperty("id", id);
        value.addProperty("kind", kind); value.addProperty("label", label); return value;
    }
    /** Bounded binary mirror; the client receives copies, never authoritative inventory. */
    public byte[] mirror() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(BuiltInRegistries.MENU.getKey(menu().getType()));
            buffer.writeVarInt(menu().containerId); buffer.writeComponent(actor.title());
            byte[] opening = actor.openingData(); JsonObject customState = new JsonObject();
            for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(menu())) {
                opening = adapter.initializationData(this); customState = adapter.clientState(this); break;
            }
            if (opening.length > 32600) throw new IllegalArgumentException("opening_data_too_large");
            buffer.writeByteArray(opening);
            var tile = targetPosition == null ? null : maid.level().getBlockEntity(targetPosition);
            buffer.writeBoolean(tile != null);
            if (tile != null) {
                buffer.writeBlockPos(targetPosition);
                var tag = tile.getUpdateTag().copy();
                tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tile.getType()).toString());
                buffer.writeNbt(tag);
            }
            buffer.writeVarInt(menu().slots.size());
            if (menu().slots.size() > 512) throw new IllegalArgumentException("menu_too_large");
            for (Slot slot : menu().slots) buffer.writeItem(slot.getItem());
            buffer.writeItem(menu().getCarried());
            for (int i = 0; i < 41; i++) buffer.writeItem(actor.getInventory().getItem(i));
            var data = ((GuiMenuDataAccessor) menu()).touhouAIFun$dataSlots();
            buffer.writeVarInt(data.size()); for (DataSlot slot : data) buffer.writeInt(slot.get());
            buffer.writeBoolean(actor.offers() != null);
            if (actor.offers() != null) buffer.writeNbt(actor.offers().createTag());
            buffer.writeUtf(customState.toString(), 16384);
            actor.protocol.write(buffer);
            if (buffer.readableBytes() > 900 * 1024) throw new IllegalArgumentException("mirror_too_large");
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes;
        } finally { buffer.release(); }
    }
}
