package com.wjx.touhou_aifun.maid.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.google.gson.*;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.action.MaidActionLease;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** All entry points and mutations execute on the owning server thread. */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class MaidGuiSessionManager {
    private static final Map<UUID, MaidGuiSession> SESSIONS = new HashMap<>();
    private static final Map<UUID, Opening> OPENING = new HashMap<>();
    private static final Map<UUID, Task> TASKS = new HashMap<>();
    private MaidGuiSessionManager() { }
    private static final class Task {
        final Object callback;
        final GuiWaitBudget budget = new GuiWaitBudget();
        final Map<String, JsonObject> results = new HashMap<>();
        final Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        final Map<String, Integer> delivered = new HashMap<>();
        long activeTicks;
        int actions;
        boolean stopped;
        GuiWaitPolicy policy;
        int explicitSeconds;
        String goalItem = "";
        int goalCount;
        Task(Object callback) { this.callback = callback; }
    }
    private record Opening(MaidGuiSession session, BlockPos target, BlockPos approach, UUID entity,
                           String blockId, int itemSlot, long deadline, CompletableFuture<JsonObject> future) { }
    public static MaidGuiSession session(UUID maid) { return SESSIONS.get(maid); }
    public static JsonObject error(String reason) {
        JsonObject result = new JsonObject(); result.addProperty("status", "failed"); result.addProperty("error", reason); return result;
    }
    public static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }
    public static int number(JsonObject json, String key, int fallback, int min, int max) {
        int value = json.has(key) ? json.get(key).getAsInt() : fallback;
        if (value < min || value > max) throw new IllegalArgumentException("invalid_" + key);
        return value;
    }
    public static CompletableFuture<JsonObject> call(LLMCallback callback, String tool, String actionId, JsonObject request) {
        EntityMaid maid = callback.getMaid();
        if (!(maid.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return CompletableFuture.completedFuture(error("wrong_thread"));
        }
        if (ChatFlowManager.isSuperseded(maid.getUUID(), callback)) return CompletableFuture.completedFuture(error("superseded"));
        Task task = TASKS.get(maid.getUUID());
        if (task != null && task.callback != callback) { cancel(maid.getUUID(), "superseded"); task = null; }
        if (task == null) { task = new Task(callback); TASKS.put(maid.getUUID(), task); }
        if (task.stopped) return CompletableFuture.completedFuture(error("stopped_by_owner"));
        String key = tool + ":" + actionId;
        if (task.results.containsKey(key)) return CompletableFuture.completedFuture(task.results.get(key).deepCopy());
        if (task.pending.containsKey(key)) return task.pending.get(key).thenApply(JsonObject::deepCopy);
        CompletableFuture<JsonObject> future;
        try {
            if (tool.equals("open_gui")) {
                if (++task.actions > 64) throw new IllegalArgumentException("action_limit");
                future = open(maid, callback, request, task);
            }
            else {
                MaidGuiSession session = SESSIONS.get(maid.getUUID());
                if (session == null || !session.id.toString().equals(string(request, "session_id", ""))) {
                    return CompletableFuture.completedFuture(error("session_expired_or_mismatch"));
                }
                if (session.waitFuture != null || GuiClientBridge.busy(session)) return CompletableFuture.completedFuture(error("operation_in_progress"));
                session.actor.align();
                if (!session.menu().stillValid(session.actor)) { cancel(maid.getUUID(), "menu_invalid"); return CompletableFuture.completedFuture(error("menu_invalid")); }
                if (tool.equals("gui_action")) reserveActions(session, 1);
                switch (tool) {
                    case "inspect_gui" -> {
                        boolean visual = request.has("visual") && request.get("visual").getAsBoolean();
                        future = visual ? GuiClientBridge.request(session, "capture", request) : CompletableFuture.completedFuture(session.snapshot("ok"));
                    }
                    case "gui_action" -> {
                        String action = string(request, "action", "");
                        if (Set.of("click", "type", "scroll", "widget", "key").contains(action)) future = GuiClientBridge.request(session, "input", request);
                        else future = CompletableFuture.completedFuture(action(session, request));
                    }
                    case "wait_gui" -> future = waitFor(session, request);
                    case "close_gui" -> {
                        JsonObject result = session.snapshot("closed"); closeSession(maid.getUUID(), "closed");
                        result.addProperty("dropped_count", session.droppedCount + session.actor.droppedCount);
                        future = CompletableFuture.completedFuture(result);
                    }
                    default -> future = CompletableFuture.completedFuture(error("unknown_gui_tool"));
                }
            }
        } catch (RuntimeException e) {
            if (tool.equals("open_gui")) closeSession(maid.getUUID(), "opening_failed");
            TouhouAIFun.LOGGER.warn("Maid GUI {} rejected: {}", tool, e.toString());
            future = CompletableFuture.completedFuture(error(e.getMessage() == null ? "gui_error" : e.getMessage()));
        }
        Task captured = task;
        captured.pending.put(key, future);
        return future.thenApply(result -> {
            captured.pending.remove(key);
            JsonObject cached = result.deepCopy(); cached.remove("_gui_image");
            captured.results.put(key, cached); return result;
        });
    }
    public static void reserveActions(MaidGuiSession session, int count) {
        Task task = TASKS.get(session.maid.getUUID());
        if (task == null) return;
        if (count < 0 || task.actions + count > 64) throw new IllegalArgumentException("action_limit");
        task.actions += count; session.actionCount = task.actions;
    }
    private static CompletableFuture<JsonObject> open(EntityMaid maid, Object callback, JsonObject request, Task task) {
        closeSession(maid.getUUID(), "reopened");
        MaidEatFoodActionManager.cancelForMaid(maid.getUUID(), "GUI action started.");
        if (maid.isSleeping() || maid.isPassenger() || maid.isLeashed()) return CompletableFuture.completedFuture(error("movement_blocked"));
        GuiWaitPolicy policy = GuiWaitPolicy.parse(string(request, "wait_policy", "AUTO"));
        if (callback instanceof LLMCallback llm) {
            String userText = "";
            for (var message : llm.getMessages()) if (message.role() == com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.USER) userText = message.message();
            policy = GuiWaitIntent.resolve(userText, policy);
            task.explicitSeconds = GuiWaitIntent.maximumSeconds(userText);
        }
        if (task.policy == null) task.policy = policy;
        policy = task.policy;
        MaidGuiSession session = new MaidGuiSession(maid, callback, policy, task.budget);
        session.actionCount = task.actions;
        session.goalItem = task.goalItem; session.goalCount = task.goalCount;
        session.delivered.putAll(task.delivered);
        if (!MaidActionLease.acquire(maid.getUUID(), session)) return CompletableFuture.completedFuture(error("action_busy"));
        SESSIONS.put(maid.getUUID(), session);
        maid.setInSittingPose(false);
        int radius = number(request, "max_distance", 12, 1, 16);
        int itemSlot = number(request, "item_slot", -1, -1, 40);
        ServerLevel level = (ServerLevel) maid.level();
        UUID entityId = request.has("entity_uuid") ? UUID.fromString(request.get("entity_uuid").getAsString()) : null;
        Entity entity = entityId == null ? null : level.getEntity(entityId);
        BlockPos target = null;
        if (itemSlot < 0) {
            if (entityId != null) {
                if (entity == null || maid.distanceToSqr(entity) > radius * radius) throw new IllegalArgumentException("entity_unavailable");
                target = entity.blockPosition();
            } else if (request.has("x") && request.has("y") && request.has("z")) {
                target = new BlockPos(request.get("x").getAsInt(), request.get("y").getAsInt(), request.get("z").getAsInt());
                if (maid.distanceToSqr(Vec3.atCenterOf(target)) > radius * radius) throw new IllegalArgumentException("target_out_of_range");
            } else {
                String wanted = string(request, "target", "nearest").toLowerCase(Locale.ROOT);
                List<BlockPos> candidates = new ArrayList<>();
                for (BlockPos pos : BlockPos.betweenClosed(maid.blockPosition().offset(-radius, -Math.min(radius, 4), -radius),
                        maid.blockPosition().offset(radius, Math.min(radius, 4), radius))) {
                    if (!level.hasChunkAt(pos) || !maid.isWithinRestriction(pos) || maid.distanceToSqr(Vec3.atCenterOf(pos)) > radius * radius) continue;
                    var state = level.getBlockState(pos);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if ((wanted.equals("nearest") || wanted.equals("any") || id.contains(wanted)) && state.getMenuProvider(level, pos) != null) candidates.add(pos.immutable());
                }
                candidates.sort(Comparator.comparingDouble(pos -> maid.distanceToSqr(Vec3.atCenterOf(pos))));
                for (BlockPos candidate : candidates) if (approach(maid, candidate, radius) != null) { target = candidate; break; }
            }
            if (target == null || !level.hasChunkAt(target) || !maid.isWithinRestriction(target)) throw new IllegalArgumentException("target_not_found_or_restricted");
        } else if (!MaidGuiInventoryBinding.enabled(session.actor.getInventory(), itemSlot) || session.actor.getInventory().getItem(itemSlot).isEmpty()) {
            throw new IllegalArgumentException("item_slot_unavailable");
        }
        BlockPos approach = itemSlot >= 0 ? maid.blockPosition() : approach(maid, target, radius);
        if (approach == null) throw new IllegalArgumentException("unreachable");
        String blockId = target == null || entityId != null ? "" : BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString();
        session.targetPosition = target;
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        OPENING.put(maid.getUUID(), new Opening(session, target, approach, entityId, blockId, itemSlot, level.getGameTime() + 400, future));
        ChatFlowManager.setInFlight(maid.getUUID(), callback, future);
        tickOpening(OPENING.get(maid.getUUID()));
        return future;
    }
    private static BlockPos approach(EntityMaid maid, BlockPos target, int radius) {
        if (maid.distanceToSqr(Vec3.atCenterOf(target)) <= 2.25 * 2.25) return maid.blockPosition();
        ServerLevel level = (ServerLevel) maid.level();
        // PathNavigationRegion calls getChunk while constructing its cache. Check the entire
        // cache area before either the shared BFS or vanilla navigation can request a chunk.
        int pathRadius = Math.max(radius + 2, (int) Math.ceil(maid.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE)) + 8);
        if (!loadedRegion(level, maid.blockPosition(), pathRadius)) throw new IllegalArgumentException("path_region_not_loaded");
        MaidPathFindingBFS bfs = new MaidPathFindingBFS(maid.getNavigation().getNodeEvaluator(), (ServerLevel) maid.level(), maid, maid.blockPosition(), radius + 2F, 6);
        try {
            // canPathReach also accepts the floor below a reachable node. Navigation needs
            // the actual standing position, so select a visited node within interaction range.
            return bfs.find(pos -> !pos.equals(target) && maid.isWithinRestriction(pos)
                    && Vec3.atBottomCenterOf(pos).distanceToSqr(Vec3.atCenterOf(target)) <= 2.25 * 2.25)
                    .map(BlockPos::immutable).orElse(null);
        } finally { bfs.finish(); }
    }
    private static boolean loadedRegion(ServerLevel level, BlockPos center, int radius) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++)
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) if (!level.hasChunk(x, z)) return false;
        return true;
    }
    private static void tickOpening(Opening opening) {
        MaidGuiSession session = opening.session; EntityMaid maid = session.maid;
        ServerLevel level = (ServerLevel) maid.level();
        if (opening.future.isDone()) { closeSession(maid.getUUID(), "cancelled"); return; }
        if (level.getGameTime() >= opening.deadline) { closeSession(maid.getUUID(), "unreachable_timeout"); return; }
        Entity entity = opening.entity == null ? null : level.getEntity(opening.entity);
        BlockPos target = entity == null ? opening.target : entity.blockPosition();
        if (opening.itemSlot < 0 && (target == null || !level.hasChunkAt(target) || !maid.isWithinRestriction(target)
                || opening.entity != null && entity == null || !opening.blockId.isBlank()
                && !opening.blockId.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString()))) {
            closeSession(maid.getUUID(), "target_changed"); return;
        }
        if (opening.itemSlot < 0 && maid.distanceToSqr(Vec3.atCenterOf(target)) > 2.25 * 2.25) {
            BlockPos walk = entity == null ? opening.approach : target;
            int pathRadius = (int) Math.ceil(maid.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE)) + 8;
            if (!loadedRegion(level, maid.blockPosition(), pathRadius)) { closeSession(maid.getUUID(), "path_region_not_loaded"); return; }
            var path = maid.getNavigation().createPath(walk, entity == null ? 0 : 1);
            // Newly spawned/falling mobs cannot create a ground path yet. Retry within
            // the opening deadline rather than treating that transient state as failure.
            if (path == null || !path.canReach()) return;
            for (int i = 0; i < path.getNodeCount(); i++) if (!maid.isWithinRestriction(path.getNode(i).asBlockPos())) {
                closeSession(maid.getUUID(), "path_outside_restriction"); return;
            }
            maid.getNavigation().moveTo(path, .65);
            maid.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
            return;
        }
        maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        session.actor.align();
        if (opening.itemSlot >= 0) {
            if (opening.itemSlot != 0 && opening.itemSlot != 40) {
                ItemStack stack = session.actor.getInventory().getItem(opening.itemSlot);
                ItemStack hand = maid.getMainHandItem();
                session.actor.getInventory().setItem(opening.itemSlot, hand);
                maid.setItemInHand(InteractionHand.MAIN_HAND, stack);
                session.borrowedItemSlot = opening.itemSlot;
            }
            session.actor.gameMode.useItem(session.actor, level, session.actor.getItemInHand(
                    opening.itemSlot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND),
                    opening.itemSlot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        } else if (entity != null) {
            PlayerInteractEvent.EntityInteract event = new PlayerInteractEvent.EntityInteract(session.actor, InteractionHand.MAIN_HAND, entity);
            if (!MinecraftForge.EVENT_BUS.post(event)) session.actor.interactOn(entity, InteractionHand.MAIN_HAND);
        } else {
            if (level.getBlockState(target).is(net.minecraft.world.level.block.Blocks.ENDER_CHEST)) {
                closeSession(maid.getUUID(), "player_storage_not_bridged"); return;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
            session.actor.gameMode.useItemOn(session.actor, level, session.actor.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        }
        if (session.menu() == session.actor.inventoryMenu) { closeSession(maid.getUUID(), "no_server_menu"); return; }
        OPENING.remove(maid.getUUID()); session.lastAction = "opened";
        opening.future.complete(session.snapshot("opened"));
    }
    public static JsonObject action(MaidGuiSession session, JsonObject request) {
        if (!session.maid.isAlive() || session.maid.isRemoved()) return error("maid_unavailable");
        if (!Objects.equals(session.owner, session.maid.getOwnerUUID())) return error("owner_changed");
        if (ChatFlowManager.isSuperseded(session.maid.getUUID(), session.callback)) return error("superseded");
        if (!session.menu().stillValid(session.actor)) return error("menu_invalid");
        String kind = string(request, "action", "");
        session.lastAction = kind;
        switch (kind) {
            case "close_menu" -> {
                JsonObject result = session.snapshot("closed"); closeSession(session.maid.getUUID(), "closed");
                result.addProperty("dropped_count", session.droppedCount + session.actor.droppedCount); return result;
            }
            case "transfer" -> {
                int from = number(request, "slot", -1, 0, session.menu().slots.size() - 1);
                int to = number(request, "to_slot", -1, 0, session.menu().slots.size() - 1);
                int count = number(request, "count", 64, 1, 64);
                int moved = transfer(session, from, to, count, true);
                JsonObject result = session.snapshot(moved > 0 ? "ok" : "no_change"); result.addProperty("moved_count", moved); return result;
            }
            case "click_slot", "quick_move" -> {
                int slot = number(request, "slot", -1, 0, session.menu().slots.size() - 1);
                checkObserved(session, slot);
                ItemStack source = session.menu().getSlot(slot).getItem().copy();
                int before = inventoryCount(session, MaidGuiSession.itemId(source));
                session.menu().clicked(slot, number(request, "button", 0, 0, 1),
                        kind.equals("quick_move") ? ClickType.QUICK_MOVE : ClickType.PICKUP, session.actor);
                int moved = Math.max(0, inventoryCount(session, MaidGuiSession.itemId(source)) - before);
                if (session.menu().getSlot(slot).container != session.actor.getInventory()) session.delivered.merge(MaidGuiSession.itemId(source), moved, Integer::sum);
            }
            case "button" -> {
                if (!session.menu().clickMenuButton(session.actor, number(request, "button_id", -1, 0, 65535))) return error("button_rejected_or_requires_adapter");
            }
            case "rename" -> {
                if (!(session.menu() instanceof AnvilMenu anvil)) return error("not_an_anvil");
                String text = string(request, "text", ""); if (text.length() > 50) return error("text_too_long"); anvil.setItemName(text);
            }
            case "trade" -> {
                if (!(session.menu() instanceof MerchantMenu merchant)) return error("not_a_merchant");
                int trade = number(request, "trade_index", -1, 0, 255);
                if (session.actor.offers() == null || trade >= session.actor.offers().size()) return error("trade_unavailable");
                merchant.setSelectionHint(trade); merchant.tryMoveItems(trade);
            }
            case "backpack_to_cursor", "cursor_to_backpack" -> {
                int slot = number(request, "backpack_slot", -1, 0, session.maid.getAvailableBackpackInv().getSlots() - 1);
                ItemStack previous = session.observedBackpack.get(slot);
                if (previous == null || !ItemStack.matches(previous, session.maid.getAvailableBackpackInv().getStackInSlot(slot))
                        || !ItemStack.matches(session.observedCarried, session.menu().getCarried())) return error("stale_slots_reinspect");
                int count = number(request, "count", 64, 1, 64);
                if (kind.equals("backpack_to_cursor")) {
                    if (!session.menu().getCarried().isEmpty()) return error("cursor_not_empty");
                    session.menu().setCarried(session.maid.getAvailableBackpackInv().extractItem(slot, count, false));
                } else {
                    ItemStack carried = session.menu().getCarried();
                    ItemStack portion = carried.copyWithCount(Math.min(count, carried.getCount()));
                    ItemStack remainder = session.maid.getAvailableBackpackInv().insertItem(slot, portion, false);
                    carried.shrink(portion.getCount() - remainder.getCount());
                }
            }
            default -> {
                boolean handled = false;
                for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(session.menu()) && adapter.action(session, request)) { handled = true; break; }
                if (!handled) return error("action_requires_adapter");
            }
        }
        session.menu().broadcastChanges();
        Task task = TASKS.get(session.maid.getUUID()); if (task != null) task.delivered.putAll(session.delivered);
        session.frame = 0;
        return session.snapshot("ok");
    }
    private static int inventoryCount(MaidGuiSession session, String item) {
        int count = 0;
        for (int i = 0; i < 41; i++) { ItemStack stack = session.actor.getInventory().getItem(i); if (MaidGuiSession.itemId(stack).equals(item)) count += stack.getCount(); }
        return count;
    }
    public static void checkObserved(MaidGuiSession session, int slot) {
        ItemStack previous = session.observedSlots.get(slot);
        if (previous == null || !ItemStack.matches(previous, session.menu().getSlot(slot).getItem())
                || !ItemStack.matches(session.observedCarried, session.menu().getCarried())) throw new IllegalArgumentException("stale_slots_reinspect");
    }
    public static int transfer(MaidGuiSession session, int from, int to, int count, boolean observed) {
        if (from == to || !session.menu().getCarried().isEmpty()) throw new IllegalArgumentException("invalid_transfer_or_cursor_busy");
        if (observed) { checkObserved(session, from); checkObserved(session, to); }
        Slot source = session.menu().getSlot(from), target = session.menu().getSlot(to);
        ItemStack stack = source.getItem().copy(), destination = target.getItem();
        if (stack.isEmpty() || !source.mayPickup(session.actor) || !target.mayPlace(stack)) return 0;
        if (!destination.isEmpty() && !ItemStack.isSameItemSameTags(stack, destination)) return 0;
        int amount = Math.min(count, Math.min(stack.getCount(), target.getMaxStackSize(stack) - destination.getCount()));
        if (amount <= 0) return 0;
        boolean partialFurnace = source instanceof FurnaceResultSlot && amount < stack.getCount();
        if (amount < stack.getCount() && !source.mayPlace(stack) && !partialFurnace) throw new IllegalArgumentException("indivisible_output_stack");
        int before = destination.getCount();
        // Furnace output is divisible. Use the same guarded take/onTake primitive used by
        // AbstractContainerMenu's PICKUP branch for an exact count; crafting bundles stay whole.
        if (partialFurnace) session.menu().setCarried(source.safeTake(amount, Integer.MAX_VALUE, session.actor));
        else session.menu().clicked(from, 0, ClickType.PICKUP, session.actor);
        for (int i = 0; i < amount && !session.menu().getCarried().isEmpty(); i++) session.menu().clicked(to, 1, ClickType.PICKUP, session.actor);
        if (!session.menu().getCarried().isEmpty()) {
            if (source.mayPlace(session.menu().getCarried())) session.menu().clicked(from, 0, ClickType.PICKUP, session.actor);
            if (!session.menu().getCarried().isEmpty()) {
                session.droppedCount += MaidGuiInventoryBinding.giveBack(session.maid, session.menu().getCarried().copy()); session.menu().setCarried(ItemStack.EMPTY);
            }
        }
        int moved = Math.max(0, target.getItem().getCount() - before);
        if (source.container != session.actor.getInventory() && target.container == session.actor.getInventory()) session.delivered.merge(MaidGuiSession.itemId(stack), moved, Integer::sum);
        session.menu().broadcastChanges(); return moved;
    }
    private static CompletableFuture<JsonObject> waitFor(MaidGuiSession session, JsonObject request) {
        String goalItem = string(request, "item", session.goalItem);
        if (!session.goalItem.isBlank() && !goalItem.equals(session.goalItem)) throw new IllegalArgumentException("goal_changed_start_new_turn");
        session.goalItem = goalItem;
        if (session.goalItem.isBlank()) throw new IllegalArgumentException("goal_item_required");
        int goal = number(request, "count", session.goalCount > 0 ? session.goalCount : 1, 1, 4096);
        if (session.goalCount > 0 && session.goalCount != goal) throw new IllegalArgumentException("goal_changed_start_new_turn");
        session.goalCount = goal;
        session.goalOutputSlot = number(request, "output_slot", session.menu() instanceof AbstractFurnaceMenu ? 2 : -1, -1, session.menu().slots.size() - 1);
        session.process = MaidGuiAdapters.observe(session, session.goalItem, Math.max(0, goal - session.delivered.getOrDefault(session.goalItem, 0)));
        int maximum = number(request, "max_wait_seconds", 0, 0, 86400);
        Task task = TASKS.get(session.maid.getUUID());
        if (task != null) {
            task.goalItem = session.goalItem; task.goalCount = goal;
            if (task.explicitSeconds > 0) maximum = maximum > 0 ? Math.min(maximum, task.explicitSeconds) : task.explicitSeconds;
        }
        long estimate = session.process.status() == GuiProcessState.Status.BLOCKED ? -1 : session.process.remainingTicks();
        session.waitBudget.configure(session.policy, estimate, TouhouAIFunConfig.GUI_MAX_WAIT_SECONDS.get() * 20L, maximum * 20L);
        if (session.policy == GuiWaitPolicy.NO_WAIT || session.waitBudget.exhausted()) {
            session.startOnly = true;
            session.startConfirmationTick = session.maid.level().getGameTime();
        }
        session.waiting = new GuiWaitTracker(session.maid.level().getGameTime());
        session.lastAction = session.startOnly ? "starting" : "waiting";
        session.waitFuture = new CompletableFuture<>();
        ChatFlowManager.setInFlight(session.maid.getUUID(), session.callback, session.waitFuture);
        return session.waitFuture;
    }
    private static void collect(MaidGuiSession session) {
        int outstanding = session.goalCount - session.delivered.getOrDefault(session.goalItem, 0);
        if (outstanding <= 0) return;
        for (int from = 0; from < session.menu().slots.size(); from++) {
            Slot output = session.menu().getSlot(from);
            if (session.goalOutputSlot >= 0 ? from != session.goalOutputSlot : output.container == session.actor.getInventory()
                    || output.mayPlace(output.getItem())) continue;
            if (!MaidGuiSession.itemId(output.getItem()).equals(session.goalItem)) continue;
            // Do not split an indivisible crafting output. Report actual delivery, including a
            // recipe's minimum bundle, rather than discard the remainder.
            boolean indivisible = !output.mayPlace(output.getItem()) && !(output instanceof FurnaceResultSlot);
            int requested = Math.max(outstanding, indivisible ? output.getItem().getCount() : outstanding);
            for (int to = 0; to < session.menu().slots.size() && outstanding > 0; to++) {
                Slot slot = session.menu().getSlot(to);
                if (slot.container != session.actor.getInventory() || slot.getContainerSlot() == 0) continue;
                if (indivisible && slot.getMaxStackSize(output.getItem()) - slot.getItem().getCount() < output.getItem().getCount()) continue;
                int moved = transfer(session, from, to, Math.min(64, requested), false);
                outstanding -= moved; requested -= moved;
            }
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        GuiClientBridge.tick();
        for (MaidGuiSession session : List.copyOf(SESSIONS.values())) {
            try {
                long now = session.maid.level().getGameTime();
                Task task = TASKS.get(session.maid.getUUID());
                if (session.waitFuture == null && task != null) task.activeTicks += Math.max(0, now - session.lastTick);
                session.lastTick = now;
                if (!session.maid.isAlive() || session.maid.isRemoved() || ChatFlowManager.isSuperseded(session.maid.getUUID(), session.callback)
                        || !Objects.equals(session.owner, session.maid.getOwnerUUID())) { cancel(session.maid.getUUID(), "cancelled"); continue; }
                if (task != null && task.activeTicks >= 6000) { cancel(session.maid.getUUID(), "active_time_limit"); continue; }
                Opening opening = OPENING.get(session.maid.getUUID());
                if (opening != null) { tickOpening(opening); continue; }
                session.actor.align();
                if (!session.menu().stillValid(session.actor)) { cancel(session.maid.getUUID(), "menu_invalid"); continue; }
                session.menu().broadcastChanges();
                if (session.waitFuture != null && now % 20 == 0) {
                    if (session.waitFuture.isDone()) { closeSession(session.maid.getUUID(), "cancelled"); continue; }
                    if (session.startOnly) {
                        session.process = MaidGuiAdapters.observe(session, session.goalItem, session.goalCount);
                        String outcome = "";
                        if (session.process.status() == GuiProcessState.Status.RUNNING || session.process.status() == GuiProcessState.Status.COMPLETED)
                            outcome = session.policy == GuiWaitPolicy.NO_WAIT ? "started_no_wait" : "started_long_task_or_budget_exhausted";
                        else if (now - session.startConfirmationTick >= 60) {
                            if (session.process.status() == GuiProcessState.Status.BLOCKED) {
                                if (session.startBlocked.equals(session.process.blockedReason())) session.startBlockedSamples++;
                                else { session.startBlocked = session.process.blockedReason(); session.startBlockedSamples = 1; }
                                if (session.startBlockedSamples >= 2) outcome = "blocked_" + session.startBlocked;
                            } else outcome = "submitted_start_unconfirmed";
                        }
                        if (!outcome.isBlank()) {
                            CompletableFuture<JsonObject> future = session.waitFuture; session.waitFuture = null;
                            JsonObject result = session.snapshot(outcome); closeSession(session.maid.getUUID(), outcome);
                            result.addProperty("dropped_count", session.droppedCount + session.actor.droppedCount); future.complete(result);
                        }
                        continue;
                    }
                    // Observe production before collection removes the output stack. Otherwise
                    // consecutive recipes wrap the raw progress and look like a stalled batch.
                    MaidGuiAdapters.observe(session, session.goalItem,
                            Math.max(0, session.goalCount - session.delivered.getOrDefault(session.goalItem, 0)));
                    collect(session);
                    session.process = MaidGuiAdapters.observe(session, session.goalItem,
                            Math.max(0, session.goalCount - session.delivered.getOrDefault(session.goalItem, 0)));
                    if (session.goalOutputSlot >= 0 && session.delivered.getOrDefault(session.goalItem, 0) < session.goalCount
                            && MaidGuiSession.itemId(session.menu().getSlot(session.goalOutputSlot).getItem()).equals(session.goalItem)) {
                        session.process = new GuiProcessState(GuiProcessState.Status.BLOCKED, "inventory_full", session.process.progress(),
                                session.process.total(), session.process.produced(), session.process.remainingTicks(), 20, true);
                    }
                    if (session.process.status() == GuiProcessState.Status.RUNNING) session.waitBudget.configure(session.policy,
                            session.process.remainingTicks(), TouhouAIFunConfig.GUI_MAX_WAIT_SECONDS.get() * 20L, 0);
                    String state = session.waiting.sample(now, session.process, session.waitBudget,
                            session.delivered.getOrDefault(session.goalItem, 0) >= session.goalCount);
                    if (task != null) task.delivered.putAll(session.delivered);
                    if (!state.equals("waiting")) {
                        CompletableFuture<JsonObject> future = session.waitFuture; session.waitFuture = null; session.waiting = null;
                        JsonObject result = session.snapshot(state);
                        if (state.equals("completed") || state.equals("still_processing") || state.equals("progress_unknown")) closeSession(session.maid.getUUID(), state);
                        result.addProperty("dropped_count", session.droppedCount + session.actor.droppedCount);
                        future.complete(result);
                    }
                }
            } catch (RuntimeException e) {
                TouhouAIFun.LOGGER.error("Maid GUI session failed", e); cancel(session.maid.getUUID(), "gui_runtime_error");
            }
        }
    }
    public static void finish(Object callback) {
        for (var entry : List.copyOf(TASKS.entrySet())) if (entry.getValue().callback == callback) cancel(entry.getKey(), "conversation_finished");
    }
    public static void cancel(UUID maid, String reason) {
        Task task = TASKS.get(maid);
        if (reason.equals("stopped_by_owner") && task != null) task.stopped = true;
        closeSession(maid, reason);
        if (!reason.equals("stopped_by_owner")) TASKS.remove(maid);
    }
    private static void closeSession(UUID maid, String reason) {
        MaidGuiSession session = SESSIONS.remove(maid); Opening opening = OPENING.remove(maid);
        if (session == null) return;
        try {
            if (session.menu() != session.actor.inventoryMenu) session.actor.closeContainer();
            if (session.borrowedItemSlot > 0) {
                ItemStack hand = session.maid.getMainHandItem();
                ItemStack originalHand = session.maid.getMaidInv().getStackInSlot(session.borrowedItemSlot - 1);
                session.maid.getMaidInv().setStackInSlot(session.borrowedItemSlot - 1, hand);
                session.maid.setItemInHand(InteractionHand.MAIN_HAND, originalHand);
            }
        } finally {
            session.maid.getNavigation().stop(); session.maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            MaidActionLease.release(maid, session); GuiClientBridge.cancel(session);
            if (opening != null && !opening.future.isDone()) opening.future.complete(error(reason));
            if (session.waitFuture != null && !session.waitFuture.isDone()) session.waitFuture.complete(error(reason));
        }
    }
    public static void clearAll() { for (UUID maid : List.copyOf(SESSIONS.keySet())) cancel(maid, "server_stopped"); TASKS.clear(); }
    @SubscribeEvent public static void death(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof EntityMaid maid) cancel(maid.getUUID(), "maid_died");
    }
    public static net.minecraft.nbt.ListTag saveTemporary(EntityMaid maid) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        MaidGuiSession session = SESSIONS.get(maid.getUUID());
        if (session == null || session.menu() == session.actor.inventoryMenu) return list;
        if (!session.menu().getCarried().isEmpty()) list.add(session.menu().getCarried().save(new net.minecraft.nbt.CompoundTag()));
        // Snapshot only menu-owned transient inventories. Persistent block/container slots are
        // already saved by their owners and must never be replayed on load.
        Set<net.minecraft.world.Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Slot slot : session.menu().slots) if ((slot.container instanceof CraftingContainer
                || slot.container instanceof MerchantContainer
                || slot.container instanceof net.minecraft.world.SimpleContainer && (session.menu() instanceof ItemCombinerMenu
                    || session.menu() instanceof StonecutterMenu || session.menu() instanceof GrindstoneMenu
                    || session.menu() instanceof CartographyTableMenu || session.menu() instanceof LoomMenu
                    || session.menu() instanceof EnchantmentMenu || session.menu() instanceof BeaconMenu)
                || MaidGuiAdapters.gui().stream().anyMatch(adapter -> adapter.supports(session.menu()) && adapter.transientInventory(session, slot.container))) && seen.add(slot.container)) {
            for (int i = 0; i < slot.container.getContainerSize(); i++) {
                // Merchant result is a preview until onTake consumes payment. Replaying it
                // alongside the saved payment would duplicate an unpurchased item.
                if (slot.container instanceof MerchantContainer && i == 2) continue;
                if (!slot.container.getItem(i).isEmpty()) list.add(slot.container.getItem(i).save(new net.minecraft.nbt.CompoundTag()));
            }
        }
        return list;
    }
}
