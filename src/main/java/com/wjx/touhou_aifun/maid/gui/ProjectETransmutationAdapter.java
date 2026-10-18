package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.gameObjs.container.TransmutationContainer;
import moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory;
import moze_intel.projecte.gameObjs.items.Tome;
import moze_intel.projecte.utils.EMCHelper;
import moze_intel.projecte.utils.PlayerHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Loaded only with ProjectE. Owner capability stays authoritative; no owner inventory/menu is impersonated. */
public final class ProjectETransmutationAdapter implements MaidGuiAdapter {
    private static final Set<String> ACTIONS = Set.of("projecte_search", "projecte_deposit", "projecte_exchange");
    @Override public boolean supports(AbstractContainerMenu menu) { return menu instanceof TransmutationContainer; }
    @Override public boolean semanticOnly() { return true; }

    private static ServerPlayer owner(MaidGuiSession session) {
        if (!Objects.equals(session.owner, session.maid.getOwnerUUID())) throw new IllegalArgumentException("owner_changed");
        if (!(session.maid.getOwner() instanceof ServerPlayer owner) || !Objects.equals(session.owner, owner.getUUID()) || !owner.isAlive() || owner.isRemoved()
                || owner.hasDisconnected()) throw new IllegalArgumentException("projecte_owner_unavailable");
        return owner;
    }
    private static IKnowledgeProvider knowledge(ServerPlayer owner) {
        return owner.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElseThrow(() -> new IllegalArgumentException("projecte_owner_knowledge_unavailable"));
    }
    @Override public JsonObject state(MaidGuiSession session) {
        JsonObject state = new JsonObject(); state.addProperty("adapter", "projecte_transmutation");
        state.addProperty("account", "owner");
        if (session.owner != null) state.addProperty("owner_uuid", session.owner.toString());
        state.addProperty("notice", "Use projecte_search, projecte_deposit(backpack_slot,count), projecte_exchange(item,selection,count). EMC and knowledge are the owner's live account; only unlocked maid backpack is used. Native slots are not this account. No owner input/lock slots or stored Klein Star EMC are touched.");
        try {
            var provider = knowledge(owner(session));
            state.addProperty("emc", provider.getEmc().toString());
            state.addProperty("full_knowledge", provider.hasFullKnowledge());
            state.addProperty("known_variants", provider.getKnowledge().size());
        } catch (IllegalArgumentException unavailable) { state.addProperty("unavailable_reason", unavailable.getMessage()); }
        return state;
    }
    @Override public JsonArray controls(MaidGuiSession session) {
        JsonArray result = new JsonArray();
        for (String action : List.of("projecte_search", "projecte_deposit", "projecte_exchange")) {
            JsonObject control = new JsonObject(); control.addProperty("id", action); control.addProperty("kind", "semantic_action"); result.add(control);
        }
        return result;
    }
    @Override public void validateAction(MaidGuiSession session, JsonObject request) {
        String action = MaidGuiSessionManager.string(request, "action", "");
        if (!ACTIONS.contains(action) && !action.equals("close_menu")) throw new IllegalArgumentException("use_projecte_semantic_actions");
    }
    private static void check(MaidGuiSession session, boolean inventory) {
        if (!session.maid.isAlive() || session.maid.isRemoved() || ChatFlowManager.isSuperseded(session.maid.getUUID(), session.callback))
            throw new IllegalArgumentException("projecte_session_unavailable");
        if (session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task
                && (task.stopped() || !task.task.pendingAmendment.isBlank())) throw new IllegalArgumentException("projecte_task_stopped_or_amended");
        if (!session.menu().stillValid(session.actor) || session.targetPosition == null
                || !session.maid.level().hasChunkAt(session.targetPosition)
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(session.maid.level().getBlockState(session.targetPosition).getBlock()).toString().equals("projecte:transmutation_table")
                || session.maid.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.targetPosition)) > 64)
            throw new IllegalArgumentException("projecte_table_unavailable");
        if (!session.menu().getCarried().isEmpty()) throw new IllegalArgumentException("cursor_not_empty");
        if (inventory) {
            var backpack = session.maid.getAvailableBackpackInv();
            for (int slot = 0; slot < backpack.getSlots(); slot++)
                if (!session.observedBackpack.containsKey(slot) || !ItemStack.matches(session.observedBackpack.get(slot), backpack.getStackInSlot(slot)))
                    throw new IllegalArgumentException("stale_slots_reinspect");
        }
    }
    private static String selection(ItemInfo info) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.write(new net.minecraft.nbt.CompoundTag()).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static List<ItemInfo> candidates(IKnowledgeProvider provider, String item, String text) {
        String filter = text.toLowerCase(Locale.ROOT);
        return provider.getKnowledge().stream().filter(provider::hasKnowledge)
                .filter(info -> item.isBlank() || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(info.getItem()).toString().equals(item))
                .filter(info -> EMCHelper.getEmcValue(info) > 0)
                .filter(info -> filter.isBlank() || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(info.getItem()).toString().toLowerCase(Locale.ROOT).contains(filter)
                        || info.createStack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter))
                .sorted(Comparator.comparing((ItemInfo info) -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(info.getItem()).toString()).thenComparing(ProjectETransmutationAdapter::selection)).toList();
    }
    @Override public JsonObject execute(MaidGuiSession session, JsonObject request) {
        String action = MaidGuiSessionManager.string(request, "action", "");
        if (!ACTIONS.contains(action)) return null;
        check(session, !action.equals("projecte_search"));
        ServerPlayer owner = owner(session); IKnowledgeProvider provider = knowledge(owner);
        BigInteger before = provider.getEmc();
        JsonObject receipt = switch (action) {
            case "projecte_search" -> search(provider, request);
            case "projecte_deposit" -> deposit(session, owner, provider, request);
            case "projecte_exchange" -> exchange(session, provider, request);
            default -> throw new IllegalStateException();
        };
        session.lastAction = action; session.frame = 0;
        JsonObject result = session.snapshot(receipt.has("error") ? "partial" : "ok");
        for (var entry : receipt.entrySet()) result.add(entry.getKey(), entry.getValue());
        result.addProperty("emc_before", before.toString()); result.addProperty("emc_after", provider.getEmc().toString());
        // Mutations and receipts are settled before packets; a disconnected client cannot undo server accounting.
        if (!action.equals("projecte_search")) {
            try { provider.syncEmc(owner); PlayerHelper.updateScore(owner, PlayerHelper.SCOREBOARD_EMC, provider.getEmc()); }
            catch (RuntimeException syncFailed) { result.addProperty("sync_warning", "projecte_owner_client_sync_failed_server_settlement_retained"); }
        }
        return result;
    }
    private static JsonObject search(IKnowledgeProvider provider, JsonObject request) {
        String item = MaidGuiSessionManager.string(request, "item", ""), text = MaidGuiSessionManager.string(request, "text", "");
        int offset = MaidGuiSessionManager.number(request, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = MaidGuiSessionManager.number(request, "limit", 8, 1, 16);
        var matches = candidates(provider, item, text); JsonArray rows = new JsonArray();
        int end = Math.min(offset, matches.size());
        for (; end < matches.size() && rows.size() < limit; end++) {
            var info = matches.get(end); long price = EMCHelper.getEmcValue(info);
            JsonObject row = MaidGuiSession.stack(info.createStack()); row.addProperty("selection", selection(info));
            if (info.hasNBT()) {
                String nbt = info.getNBT().toString();
                if (nbt.getBytes(StandardCharsets.UTF_8).length <= 2048) row.addProperty("nbt", nbt);
                else row.addProperty("nbt_omitted", true);
            }
            row.addProperty("unit_emc", Long.toString(price));
            row.addProperty("affordable_count", provider.getEmc().divide(BigInteger.valueOf(price)).toString()); rows.add(row);
        }
        JsonObject result = new JsonObject(); result.add("projecte_results", rows); result.addProperty("total_matches", matches.size());
        result.addProperty("has_more", end < matches.size()); if (end < matches.size()) result.addProperty("next_offset", end);
        return result;
    }
    private static JsonObject deposit(MaidGuiSession session, ServerPlayer owner, IKnowledgeProvider provider, JsonObject request) {
        var backpack = session.maid.getAvailableBackpackInv();
        int slot = MaidGuiSessionManager.number(request, "backpack_slot", -1, 0, backpack.getSlots() - 1);
        int count = MaidGuiSessionManager.number(request, "count", 1, 1, 64);
        ItemStack source = backpack.getStackInSlot(slot).copy();
        if (source.isEmpty() || source.getCount() < count) throw new IllegalArgumentException("projecte_insufficient_backpack_items");
        long unit = EMCHelper.getEmcSellValue(source);
        if (unit <= 0 && !(source.getItem() instanceof Tome)) throw new IllegalArgumentException("projecte_item_has_no_sell_emc");
        ItemStack simulated = backpack.extractItem(slot, count, true);
        if (simulated.getCount() != count || !ItemStack.isSameItemSameTags(source, simulated)) throw new IllegalArgumentException("projecte_backpack_extraction_denied");
        MaidGuiSessionManager.checkTaskTransferAllowance(session,MaidGuiSession.itemId(source),count,false,false);
        ItemStack removed = backpack.extractItem(slot, count, false);
        if (removed.getCount() != count || !ItemStack.isSameItemSameTags(source, removed)) {
            if (!removed.isEmpty()) session.menu().setCarried(removed);
            throw new IllegalArgumentException("projecte_extraction_changed_cursor_retained");
        }
        BigInteger gain = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(count));
        provider.setEmc(provider.getEmc().add(gain));
        MaidGuiSessionManager.recordTaskTransfer(session, MaidGuiSession.itemId(removed), count, true, false);
        // ProjectE applies persistent NBT cleaning and PlayerAttemptLearnEvent using the real owner.
        JsonObject result = new JsonObject(); result.addProperty("deposited_item", MaidGuiSession.itemId(removed));
        result.addProperty("deposited_count", count); result.addProperty("emc_gained", gain.toString());
        try { new TransmutationInventory(owner).handleKnowledge(removed); }
        catch (RuntimeException learnFailed) { result.addProperty("error", "projecte_learning_hook_failed_after_deposit"); }
        result.addProperty("learned", provider.hasKnowledge(removed)); return result;
    }
    private static JsonObject exchange(MaidGuiSession session, IKnowledgeProvider provider, JsonObject request) {
        String item = MaidGuiSessionManager.string(request, "item", "");
        if (item.isBlank()) throw new IllegalArgumentException("item_required");
        String selected = MaidGuiSessionManager.string(request, "selection", "");
        var matches = candidates(provider, item, "").stream().filter(info -> selected.isBlank() || selection(info).equals(selected)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("projecte_item_not_learned_or_no_emc_search_again");
        if (matches.size() != 1) throw new IllegalArgumentException("projecte_ambiguous_variant_use_search_selection");
        ItemInfo info = matches.get(0); ItemStack output = info.createStack();
        int count = MaidGuiSessionManager.number(request, "count", 1, 1, 4096);
        MaidGuiSessionManager.checkTaskTransferAllowance(session,MaidGuiSession.itemId(output),count,true,false);
        long unit = EMCHelper.getEmcValue(info); BigInteger cost = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(count));
        if (provider.getEmc().compareTo(cost) < 0) throw new IllegalArgumentException("projecte_insufficient_emc");
        var backpack = session.maid.getAvailableBackpackInv();
        // A separate simulated handler cannot reproduce mod slot rules. Query each real slot and
        // reserve every accepted portion before inserting anything or deducting EMC.
        List<ItemStack> portions = new ArrayList<>(); int left = count;
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack offer = output.copyWithCount(Math.min(left, output.getMaxStackSize()));
            int accepted = left == 0 ? 0 : offer.getCount() - backpack.insertItem(slot, offer, true).getCount();
            portions.add(output.copyWithCount(accepted)); left -= accepted;
        }
        if (left != 0) {
            var storage=MaidBackpackSpace.describe(backpack,output,count);storage.addProperty("simulated_accept_count",count-left);
            return MaidBackpackSpace.failure(storage,"projecte_backpack_full","projecte_item_rejected_by_backpack");
        }
        int inventoryBefore = matchingCount(session, output);
        provider.setEmc(provider.getEmc().subtract(cost));
        int delivered;
        boolean failed = false;
        try {
            for (int slot = 0; slot < portions.size(); slot++) if (!portions.get(slot).isEmpty())
                backpack.insertItem(slot, portions.get(slot), false);
        } catch (RuntimeException insertionFailed) { failed = true; }
        finally {
            // Reserve payment first. If a mod changes/rejects insertion after simulation, refund
            // only unproduced items, including when it throws after inserting an earlier stack.
            delivered = Math.max(0, Math.min(count, matchingCount(session, output) - inventoryBefore));
            provider.setEmc(provider.getEmc().add(BigInteger.valueOf(unit).multiply(BigInteger.valueOf(count - delivered))));
        }
        BigInteger spent = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(delivered));
        if (delivered > 0) MaidGuiSessionManager.recordCraftedOutput(session, item, delivered);
        JsonObject result = new JsonObject(); result.addProperty("exchanged_item", item); result.addProperty("requested_count", count);
        result.addProperty("exchanged_count", delivered); result.addProperty("emc_spent", spent.toString()); result.addProperty("selection", selection(info));
        if (failed || delivered != count) result.addProperty("error", "projecte_inventory_changed_partial_exchange");
        return result;
    }
    private static int matchingCount(MaidGuiSession session, ItemStack item) {
        int count = 0; var backpack = session.maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stored = backpack.getStackInSlot(slot);
            if (ItemStack.isSameItemSameTags(stored, item)) count += stored.getCount();
        }
        return count;
    }
}
