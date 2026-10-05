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
    private static final Map<UUID, Long> NEXT_PATH = new HashMap<>();
    private static final Map<UUID,com.wjx.touhou_aifun.chat.agent.AgentTiming.Span> WALK_TIMINGS=new java.util.concurrent.ConcurrentHashMap<>();
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
        if (json.has(key) && json.get(key).getAsDouble() != value) throw new IllegalArgumentException("invalid_" + key);
        if (value < min || value > max) throw new IllegalArgumentException("invalid_" + key);
        return value;
    }
    private static int sourceSlot(JsonObject request,int maximum) {
        if(request.has("slot") && request.has("from_slot") && request.get("slot").getAsDouble()!=request.get("from_slot").getAsDouble())
            throw new IllegalArgumentException("conflicting_slot_and_from_slot");
        if(!request.has("slot") && !request.has("from_slot")) throw new IllegalArgumentException("source_slot_required_use_observed_slot_or_from_slot");
        return number(request,request.has("slot")?"slot":"from_slot",-1,0,maximum);
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
                    case "gui_batch" -> future = CompletableFuture.completedFuture(batch(session, request));
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
            String reason = e.getMessage() == null ? "gui_error" : e.getMessage();
            JsonObject rejected = error(reason);
            if (tool.equals("open_gui")) {
                MaidGuiSession active = SESSIONS.get(maid.getUUID());
                if (active != null) rejected = openingError(active, reason);
                closeSession(maid.getUUID(), "opening_failed");
            }
            TouhouAIFun.LOGGER.warn("Maid GUI {} rejected: {}", tool, e.toString());
            future = CompletableFuture.completedFuture(rejected);
        }
        Task captured = task;
        future = future.thenApply(value -> {
            MaidGuiSession current = SESSIONS.get(maid.getUUID());
            if (value.has("slots") && current != null) {
                JsonObject projection=current.projection.project(value,
                    tool.equals("open_gui") || tool.equals("inspect_gui") || "full".equals(string(request, "detail", "")), false);
                return callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback && !"full".equals(string(request,"detail",""))
                        ? GuiSnapshotProjection.taskView(projection) : projection;
            }
            if (tool.equals("close_gui")) return new GuiSnapshotProjection().project(value, false, true);
            return value;
        });
        captured.pending.put(key, future);
        return future.thenApply(result -> {
            captured.pending.remove(key);
            JsonObject cached = result.deepCopy(); cached.remove("_gui_image");
            captured.results.put(key, cached); return result;
        });
    }
    private static JsonObject batch(MaidGuiSession session, JsonObject request) {
        if (!request.has("expected_revision") || request.get("expected_revision").getAsLong() != session.projection.revision()) return error("stale_revision_reinspect");
        JsonArray actions = request.getAsJsonArray("actions");
        if (actions == null || actions.size() < 1 || actions.size() > 16) return error("invalid_batch_size");
        JsonArray outcomes = new JsonArray();
        // Reject unsupported operation kinds before any mutation.
        for (JsonElement action : actions) if (!action.isJsonObject() || !Set.of("transfer", "click_slot", "quick_move", "button", "rename", "trade", "backpack_to_cursor", "cursor_to_backpack").contains(string(action.getAsJsonObject(), "action", ""))) return error("unsupported_batch_action");
        JsonObject last = null;
        for (JsonElement command : actions) {
            try {
                if (ChatFlowManager.isSuperseded(session.maid.getUUID(), session.callback)) throw new IllegalStateException("cancelled");
                reserveActions(session, 1);
                last = action(session, command.getAsJsonObject());
            } catch (RuntimeException failure) { last = error(failure.getMessage()); }
            JsonObject outcome = last.deepCopy();
            outcome.remove("slots"); outcome.remove("backpack_slots"); outcome.remove("controls");
            outcomes.add(outcome);
            if (last.has("error")) break;
        }
        JsonObject result = session.snapshot(last != null && last.has("error") ? "partial" : "ok");
        result.add("action_results", outcomes);
        result.addProperty("executed_count", outcomes.size() - (last != null && last.has("error") ? 1 : 0));
        if (last != null && last.has("error")) result.add("error", last.get("error"));
        return result;
    }
    public static void reserveActions(MaidGuiSession session, int count) {
        Task task = TASKS.get(session.maid.getUUID());
        if (task == null) return;
        if (count < 0 || task.actions + count > 64) throw new IllegalArgumentException("action_limit");
        task.actions += count; session.actionCount = task.actions;
    }
    private static CompletableFuture<JsonObject> open(EntityMaid maid, Object callback, JsonObject request, Task task) {
        var selection=com.wjx.touhou_aifun.chat.agent.AgentTelemetry.start(callback,"gui_find_target");
        try {
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
        BlockPos selectedApproach = null;
        BlockPos requestedPosition = itemSlot < 0 && entityId == null ? GuiBlockTarget.coordinates(request, maid.blockPosition()) : null;
        if (itemSlot < 0) {
            if (entityId != null) {
                if (entity == null || maid.distanceToSqr(entity) > radius * radius) throw new IllegalArgumentException("entity_unavailable");
                target = entity.blockPosition();
            } else if (requestedPosition != null) {
                target = requestedPosition;
                session.targetPosition = target;
                if (maid.distanceToSqr(Vec3.atCenterOf(target)) > radius * radius) throw new IllegalArgumentException("target_out_of_range");
                String requestedRegistry=GuiBlockTarget.qualifiedRegistry(request);
                if(requestedRegistry!=null && (!level.hasChunkAt(target) || !requestedRegistry.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString())))
                    throw new IllegalArgumentException("qualified_target_block_changed_reobserve");
            } else {
                String wanted = string(request, "target", "nearest").toLowerCase(Locale.ROOT);
                List<BlockPos> candidates = new ArrayList<>();
                // Loaded chunk block-entity tables avoid inspecting thousands of ordinary blocks.
                for (int cx = (maid.blockPosition().getX() - radius) >> 4; cx <= (maid.blockPosition().getX() + radius) >> 4; cx++)
                    for (int cz = (maid.blockPosition().getZ() - radius) >> 4; cz <= (maid.blockPosition().getZ() + radius) >> 4; cz++) {
                        var chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk == null) continue;
                        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                            if (!maid.isWithinRestriction(pos) || maid.distanceToSqr(Vec3.atCenterOf(pos)) > radius * radius) continue;
                            var state = level.getBlockState(pos);
                            if (GuiBlockTarget.matches(wanted, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                                    && state.getMenuProvider(level, pos) != null) candidates.add(pos.immutable());
                        }
                    }
                candidates.sort(Comparator.comparingDouble(pos -> maid.distanceToSqr(Vec3.atCenterOf(pos))));
                Set<BlockPos> attempted = new HashSet<>(candidates);
                for (BlockPos candidate : candidates) {
                    BlockPos reachable = approach(maid, candidate, radius);
                    if (reachable != null) { target = candidate; selectedApproach = reachable; break; }
                }
                candidates.clear();
                // Workbenches and modded menus can exist without a block entity.
                if (target == null)
                for (BlockPos pos : BlockPos.betweenClosed(maid.blockPosition().offset(-radius, -Math.min(radius, 4), -radius),
                        maid.blockPosition().offset(radius, Math.min(radius, 4), radius))) {
                    if (attempted.contains(pos) || !level.hasChunkAt(pos) || !maid.isWithinRestriction(pos) || maid.distanceToSqr(Vec3.atCenterOf(pos)) > radius * radius) continue;
                    var state = level.getBlockState(pos);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (GuiBlockTarget.matches(wanted, id) && state.getMenuProvider(level, pos) != null) candidates.add(pos.immutable());
                }
                candidates.sort(Comparator.comparingDouble(pos -> maid.distanceToSqr(Vec3.atCenterOf(pos))));
                for (BlockPos candidate : candidates) {
                    BlockPos reachable = approach(maid, candidate, radius);
                    if (reachable != null) { target = candidate; selectedApproach = reachable; break; }
                }
            }
            if (target == null || !level.hasChunkAt(target) || !maid.isWithinRestriction(target)) throw new IllegalArgumentException("target_not_found_or_restricted");
            session.targetPosition = target;
            if (entityId == null && request.has("target") && !GuiBlockTarget.matches(string(request, "target", "nearest").toLowerCase(Locale.ROOT),
                    BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString()))
                throw new IllegalArgumentException("target_block_mismatch");
        } else if (!MaidGuiInventoryBinding.enabled(session.actor.getInventory(), itemSlot) || session.actor.getInventory().getItem(itemSlot).isEmpty()) {
            throw new IllegalArgumentException("item_slot_unavailable");
        }
        BlockPos approach = itemSlot >= 0 ? maid.blockPosition() : selectedApproach != null ? selectedApproach : approach(maid, target, radius);
        if (approach == null) throw new IllegalArgumentException("unreachable");
        selection.finish("ok",0);
        String blockId = target == null || entityId != null ? "" : BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString();
        session.targetPosition = target;
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        long navigationStarted=System.nanoTime();
        var walking=com.wjx.touhou_aifun.chat.agent.AgentTelemetry.start(callback,"gui_navigation");WALK_TIMINGS.put(maid.getUUID(),walking);
        future.whenComplete((value,failure)-> {
            walking.finish(failure!=null?com.wjx.touhou_aifun.chat.agent.AgentTelemetry.failureStatus(failure)
                    :value!=null && value.has("error")?"error":"ok",0);
            WALK_TIMINGS.remove(maid.getUUID(),walking);
        });
        future.whenComplete((result,error)->com.wjx.touhou_aifun.chat.agent.AgentTelemetry.stage("gui_open_and_walk",navigationStarted,0));
        OPENING.put(maid.getUUID(), new Opening(session, target, approach, entityId, blockId, itemSlot, level.getGameTime() + 400, future));
        ChatFlowManager.setInFlight(maid.getUUID(), callback, future);
        NEXT_PATH.remove(maid.getUUID());
        tickOpening(OPENING.get(maid.getUUID()));
        return future;
        } finally {selection.finish("finished",0);}
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
            // The current block's centre can be in range while the maid's exact feet
            // are outside. Navigating back to that same node completes without moving.
            return bfs.find(pos -> !pos.equals(target) && !sameStandingColumn(maid,pos) && maid.isWithinRestriction(pos)
                    && Vec3.atBottomCenterOf(pos).distanceToSqr(Vec3.atCenterOf(target)) <= 2.25 * 2.25)
                    .map(BlockPos::immutable).orElse(null);
        } finally { bfs.finish(); }
    }
    private static boolean sameStandingColumn(EntityMaid maid,BlockPos pos) {
        return pos.getX()==maid.blockPosition().getX() && pos.getZ()==maid.blockPosition().getZ()
                && Math.abs(pos.getY()-maid.getY())<=1.01;
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
        if (level.getGameTime() >= opening.deadline) {
            JsonObject failure = openingError(session, "unreachable_timeout");
            failure.addProperty("phase", "navigation");
            failure.addProperty("elapsed_ticks", level.getGameTime() - (opening.deadline - 400));
            JsonArray exactPosition = new JsonArray(); exactPosition.add(maid.getX()); exactPosition.add(maid.getY()); exactPosition.add(maid.getZ());
            failure.add("maid_exact_position", exactPosition);
            JsonArray approachPosition = new JsonArray(); approachPosition.add(opening.approach.getX()); approachPosition.add(opening.approach.getY()); approachPosition.add(opening.approach.getZ());
            failure.add("approach_position", approachPosition);
            if (opening.target != null) failure.addProperty("distance_to_target", Math.sqrt(maid.distanceToSqr(Vec3.atCenterOf(opening.target))));
            var path = maid.getNavigation().getPath();
            String navigationState = path == null ? "no_path" : path.isDone() ? "path_finished" : "path_active";
            failure.addProperty("navigation_state", navigationState);
            if (path != null) {
                failure.addProperty("path_nodes", path.getNodeCount());
                failure.addProperty("next_path_node", path.getNextNodeIndex());
            }
            failure.addProperty("movement_disabled", maid.isNoAi());
            failure.addProperty("recovery_hint", "Navigation timed out before any container interaction. Reobserve the current route and approach position before retrying; changing wait_policy does not skip walking.");
            TouhouAIFun.LOGGER.warn("Maid GUI stage=navigation status=unreachable_timeout elapsed_ticks={} navigation_state={}", failure.get("elapsed_ticks"), navigationState);
            closeSession(maid.getUUID(), "unreachable_timeout", failure); return;
        }
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
            long now = level.getGameTime();
            if (now < NEXT_PATH.getOrDefault(maid.getUUID(), 0L)) return;
            NEXT_PATH.put(maid.getUUID(), now + (maid.getNavigation().isDone() ? 5 : 20));
            // Vanilla navigation may finish near a standing node, not at its exact centre.
            // Reusing that node then produces a finished one-node path forever while the
            // actual feet remain outside interaction range. Replan only this stale approach.
            if(entity==null && (sameStandingColumn(maid,walk) || maid.getNavigation().isDone()
                    && Vec3.atBottomCenterOf(walk).distanceToSqr(maid.position())<=.75*.75)) {
                BlockPos revised=approach(maid,target,Math.min(16,pathRadius));
                if(revised==null) {closeSession(maid.getUUID(),"unreachable");return;}
                walk=revised;
                opening=new Opening(session,opening.target,revised,opening.entity,opening.blockId,opening.itemSlot,opening.deadline,opening.future);
                OPENING.put(maid.getUUID(),opening);
            }
            var pathTiming=com.wjx.touhou_aifun.chat.agent.AgentTelemetry.start(session.callback,"gui_path_find");
            var path = maid.getNavigation().createPath(walk, entity == null ? 0 : 1);
            pathTiming.finish(path==null?"no_path":"ok",0);
            // Newly spawned/falling mobs cannot create a ground path yet. Retry within
            // the opening deadline rather than treating that transient state as failure.
            if (path == null || path.getEndNode() == null) return;
            // A partial path may already end inside the container's interaction range.
            // Rejecting it solely on canReach can leave a maid motionless for 400 ticks
            // even when the returned endpoint is the requested standing position.
            if (!path.canReach() && Vec3.atBottomCenterOf(path.getEndNode().asBlockPos())
                    .distanceToSqr(Vec3.atCenterOf(target)) > 2.25 * 2.25) return;
            for (int i = 0; i < path.getNodeCount(); i++) if (!maid.isWithinRestriction(path.getNode(i).asBlockPos())) {
                closeSession(maid.getUUID(), "path_outside_restriction"); return;
            }
            maid.getNavigation().moveTo(path, .65);
            maid.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
            return;
        }
        var walking=WALK_TIMINGS.get(maid.getUUID());if(walking!=null) walking.finish("arrived",0);
        var interactionTiming=com.wjx.touhou_aifun.chat.agent.AgentTelemetry.start(session.callback,"gui_interaction");
        try {
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
            var interaction = session.actor.gameMode.useItemOn(session.actor, level, session.actor.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            if (session.menu() == session.actor.inventoryMenu) {
                JsonObject failure = openingError(session, "no_server_menu");
                failure.addProperty("interaction_result", interaction.name().toLowerCase(Locale.ROOT));
                failure.addProperty("menu_provider_present", level.getBlockState(target).getMenuProvider(level, target) != null);
                OPENING.remove(maid.getUUID());
                try { closeSession(maid.getUUID(), "no_server_menu"); }
                finally { opening.future.complete(failure); }
                return;
            }
        }
        if (session.menu() == session.actor.inventoryMenu) { closeSession(maid.getUUID(), "no_server_menu"); return; }
        OPENING.remove(maid.getUUID()); session.lastAction = "opened";
        opening.future.complete(session.snapshot("opened"));
        } finally {interactionTiming.finish("finished",0);}
    }
    private static JsonObject openingError(MaidGuiSession session, String reason) {
        JsonObject result = error(reason);
        JsonArray origin = new JsonArray(); BlockPos maidPos = session.maid.blockPosition();
        origin.add(maidPos.getX()); origin.add(maidPos.getY()); origin.add(maidPos.getZ());
        result.add("maid_position", origin);
        result.addProperty("coordinate_space", "world");
        BlockPos target = session.targetPosition;
        if (target != null) {
            JsonArray position = new JsonArray(); position.add(target.getX()); position.add(target.getY()); position.add(target.getZ());
            result.add("target_position", position);
            if (session.maid.level().hasChunkAt(target)) result.addProperty("actual_block",
                    BuiltInRegistries.BLOCK.getKey(session.maid.level().getBlockState(target).getBlock()).toString());
        }
        result.add("main_hand", MaidGuiSession.stack(session.maid.getMainHandItem()));
        result.addProperty("recovery_hint", "Check actual_block and target_position. x/y/z default to world coordinates; use coordinate_space=maid_relative for current maid block offsets. Never retry the same failed coordinates blindly.");
        return result;
    }
    public static JsonObject action(MaidGuiSession session, JsonObject request) {
        String kind = string(request,"action","");
        if (kind.equals("transfer") || kind.equals("quick_move") || kind.equals("close_menu")) return actionInternal(session,request);
        var menu = session.menu();
        var external = externalItems(session,menu);var inventory = maidItems(session);
        try { return actionInternal(session,request); }
        finally { recordLedger(session,menu,external,inventory); }
    }
    private static Map<String,Integer> maidItems(MaidGuiSession session) {
        Map<String,Integer> counts=new HashMap<>();
        for(int i=0;i<41;i++) {ItemStack stack=session.actor.getInventory().getItem(i);if(!stack.isEmpty()) counts.merge(MaidGuiSession.itemId(stack),stack.getCount(),Integer::sum);}
        return counts;
    }
    private static Map<String,Integer> externalItems(MaidGuiSession session,AbstractContainerMenu menu) {
        Map<String,Integer> counts=new HashMap<>();
        Map<net.minecraft.world.Container,Set<Integer>> seen=new IdentityHashMap<>();
        for(Slot slot:menu.slots) if(slot.container!=session.actor.getInventory()
                && seen.computeIfAbsent(slot.container,k->new HashSet<>()).add(slot.getContainerSlot())) {
            var stack=slot.getItem();if(!stack.isEmpty()) counts.merge(MaidGuiSession.itemId(stack),stack.getCount(),Integer::sum);
        }
        return counts;
    }
    private static void recordLedger(MaidGuiSession session,AbstractContainerMenu menu,Map<String,Integer> external,Map<String,Integer> inventory) {
        for(var transfer:session.transferLedger.observe(external,externalItems(session,menu),inventory,maidItems(session)))
            recordTaskTransfer(session,transfer.item(),transfer.count(),!transfer.toMaid(),transfer.toMaid());
    }
    private static JsonObject actionInternal(MaidGuiSession session, JsonObject request) {
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
                int from = sourceSlot(request, session.menu().slots.size() - 1);
                int to = number(request, "to_slot", -1, 0, session.menu().slots.size() - 1);
                int count = number(request, "count", 64, 1, 64);
                String movedItem = MaidGuiSession.itemId(session.menu().getSlot(from).getItem());
                boolean toMaid = session.menu().getSlot(to).container == session.actor.getInventory();
                boolean fromMaid = session.menu().getSlot(from).container == session.actor.getInventory();
                int moved = transfer(session, from, to, count, true);
                JsonObject result = session.snapshot(moved > 0 ? "ok" : "no_change"); result.addProperty("external_transfer", fromMaid != toMaid); result.addProperty("moved_item", movedItem); result.addProperty("to_maid", toMaid); result.addProperty("moved_count", moved); return result;
            }
            case "click_slot", "quick_move" -> {
                int slot = sourceSlot(request, session.menu().slots.size() - 1);
                checkObserved(session, slot);
                ItemStack source = session.menu().getSlot(slot).getItem().copy();
                int before = inventoryCount(session, MaidGuiSession.itemId(source));
                session.menu().clicked(slot, number(request, "button", 0, 0, 1),
                        kind.equals("quick_move") ? ClickType.QUICK_MOVE : ClickType.PICKUP, session.actor);
                int delta = inventoryCount(session, MaidGuiSession.itemId(source)) - before;
                if (kind.equals("quick_move") && delta != 0) recordTaskTransfer(session,MaidGuiSession.itemId(source),Math.abs(delta),delta < 0,delta > 0);
                int moved = Math.max(0, delta);
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
        if (session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) {
            if(task.stopped()) throw new IllegalArgumentException("task_cancelled");
            if(!task.task.pendingAmendment.isBlank()) throw new IllegalArgumentException("goal_updated_before_atomic_action");
        }
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
        if (source.container != target.container && moved > 0) recordTaskTransfer(session, MaidGuiSession.itemId(stack), moved,
                source.container == session.actor.getInventory(), target.container == session.actor.getInventory());
        session.menu().broadcastChanges(); return moved;
    }
    private static void recordTaskTransfer(MaidGuiSession session, String item, int count, boolean fromMaid, boolean toMaid) {
        if (!(session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) || fromMaid == toMaid || session.targetPosition == null) return;
        JsonObject fact = new JsonObject(); fact.addProperty("external_transfer",true); fact.addProperty("moved_item",item);
        fact.addProperty("moved_count",count); fact.addProperty("to_maid",toMaid);
        fact.addProperty("dimension",session.maid.level().dimension().location().toString());
        JsonArray pos = new JsonArray(); pos.add(session.targetPosition.getX()); pos.add(session.targetPosition.getY()); pos.add(session.targetPosition.getZ());
        fact.add("target_position",pos); task.recordMutation(fact);
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
                if(session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback execution && !execution.task.pendingAmendment.isBlank()
                        && (session.waitFuture!=null || OPENING.containsKey(session.maid.getUUID()))) {
                    cancel(session.maid.getUUID(),"goal_updated_before_next_atomic_action"); continue;
                }
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
        closeSession(maid, reason, null);
    }
    private static void closeSession(UUID maid, String reason, JsonObject openingFailure) {
        NEXT_PATH.remove(maid);
        MaidGuiSession session = SESSIONS.remove(maid); Opening opening = OPENING.remove(maid);
        if (session == null) return;
        try {
            if (session.menu() != session.actor.inventoryMenu) {
                var menu=session.menu();var external=externalItems(session,menu);var inventory=maidItems(session);
                try { session.actor.closeContainer(); }
                finally { recordLedger(session,menu,external,inventory); }
            }
            if (session.borrowedItemSlot > 0) {
                ItemStack hand = session.maid.getMainHandItem();
                ItemStack originalHand = session.maid.getMaidInv().getStackInSlot(session.borrowedItemSlot - 1);
                session.maid.getMaidInv().setStackInSlot(session.borrowedItemSlot - 1, hand);
                session.maid.setItemInHand(InteractionHand.MAIN_HAND, originalHand);
            }
        } finally {
            session.maid.getNavigation().stop(); session.maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            MaidActionLease.release(maid, session); GuiClientBridge.cancel(session);
            if (opening != null && !opening.future.isDone()) opening.future.complete(openingFailure == null ? error(reason) : openingFailure);
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
