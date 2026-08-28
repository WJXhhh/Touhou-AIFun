package com.wjx.touhou_aifun.maid.action;

import com.github.tartaricacid.touhoulittlemaid.api.block.IMaidEdibleBlock;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockManager;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-thread owner for LLM-requested block-food actions. The tool only selects a target; this
 * manager keeps the action alive across ticks, lets the maid walk there, validates the block again,
 * and consumes it through TLM's public {@link IMaidEdibleBlock} extension API.
 */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidEatFoodActionManager {
    private static final int MAX_SEARCH_DISTANCE = 16;
    private static final int VERTICAL_SEARCH_DISTANCE = 4;
    private static final int ACTION_TIMEOUT_TICKS = 20 * 20;
    private static final double CONSUME_DISTANCE_SQR = 2.25 * 2.25;
    private static final float WALK_SPEED = 0.65f;

    private static final Map<UUID, ActiveAction> ACTIVE = new ConcurrentHashMap<>();

    private MaidEatFoodActionManager() {
    }

    /** Must be called on the owning server thread. */
    public static CompletableFuture<Result> start(EntityMaid maid, String requestedFood, int maxDistance,
                                                   Object callback) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        if (!(maid.level() instanceof ServerLevel level) || maid.isRemoved() || !maid.isAlive()) {
            future.complete(Result.failure("maid_unavailable", "The maid is not available in a server level."));
            return future;
        }
        if (!level.getServer().isSameThread()) {
            future.complete(Result.failure("wrong_thread", "The action must start on the server thread."));
            return future;
        }
        if (maid.isSleeping()) {
            future.complete(Result.failure("sleeping", "The maid is sleeping and cannot walk to food."));
            return future;
        }
        if (maid.isPassenger()) {
            future.complete(Result.failure("riding", "The maid is riding another entity and cannot walk to food."));
            return future;
        }
        if (maid.isLeashed()) {
            future.complete(Result.failure("leashed", "The maid is leashed and cannot walk to food."));
            return future;
        }

        // Standing up is an implied part of an explicit "go and eat" instruction.
        if (maid.isMaidInSittingPose()) {
            maid.setInSittingPose(false);
        }

        int distance = Math.max(1, Math.min(MAX_SEARCH_DISTANCE, maxDistance));
        SearchResult search = findTarget(level, maid, requestedFood, distance);
        if (search.target == null) {
            String food = displayRequestedFood(requestedFood);
            if (search.outsideRestriction) {
                future.complete(Result.failure("outside_restriction",
                        "A matching edible block was found, but it is outside the maid's allowed activity area."));
            } else if (search.matchingButUnreachable) {
                future.complete(Result.failure("unreachable",
                        "A matching edible block was found, but no reachable interaction position exists."));
            } else {
                future.complete(Result.failure("not_found",
                        "No matching edible block (%s) was found within %d blocks."
                                .formatted(food, distance)));
            }
            return future;
        }

        UUID maidId = maid.getUUID();
        ActiveAction previous = ACTIVE.remove(maidId);
        if (previous != null) {
            previous.complete(Result.failure("superseded", "A newer eat-food action replaced this one."));
        }

        ActiveAction action = new ActiveAction(maid, callback, search.target, future,
                level.getGameTime() + ACTION_TIMEOUT_TICKS);
        ACTIVE.put(maidId, action);
        action.takeControl();
        return future;
    }

    public static void cancelForMaid(UUID maidId, String reason) {
        ActiveAction action = ACTIVE.remove(maidId);
        if (action != null) {
            action.stopMoving();
            action.complete(Result.failure("cancelled", reason));
        }
    }

    public static void clearAll() {
        List.copyOf(ACTIVE.values()).forEach(action -> {
            action.stopMoving();
            action.complete(Result.failure("cancelled", "The server stopped."));
        });
        ACTIVE.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, ActiveAction> entry : List.copyOf(ACTIVE.entrySet())) {
            ActiveAction action = entry.getValue();
            if (action.future.isDone()) {
                action.stopMoving();
                ACTIVE.remove(entry.getKey(), action);
                continue;
            }
            try {
                action.tick();
            } catch (Throwable throwable) {
                TouhouAIFun.LOGGER.error("Maid eat-food action failed", throwable);
                action.finish(Result.failure("internal_error", "The eat-food action failed unexpectedly."));
            }
        }
    }

    private static SearchResult findTarget(ServerLevel level, EntityMaid maid, String requestedFood,
                                           int maxDistance) {
        BlockPos center = maid.blockPosition();
        int vertical = Math.min(VERTICAL_SEARCH_DISTANCE, maxDistance);
        int radiusSqr = maxDistance * maxDistance;
        List<Candidate> candidates = new ArrayList<>();
        boolean outsideRestriction = false;

        for (int x = -maxDistance; x <= maxDistance; x++) {
            for (int y = -vertical; y <= vertical; y++) {
                for (int z = -maxDistance; z <= maxDistance; z++) {
                    if (x * x + y * y + z * z > radiusSqr) {
                        continue;
                    }
                    BlockPos pos = center.offset(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (blockId == null || !matchesRequestedFood(requestedFood, blockId.toString())) {
                        continue;
                    }
                    for (IMaidEdibleBlock edible : MaidEdibleBlockManager.getEdibleBlocks()) {
                        if (!safeShouldMoveTo(edible, maid, pos, state)) {
                            continue;
                        }
                        if (!maid.isWithinRestriction(pos)) {
                            outsideRestriction = true;
                            continue;
                        }
                        candidates.add(new Candidate(pos.immutable(), blockId.toString(), edible,
                                maid.distanceToSqr(Vec3.atCenterOf(pos))));
                        break;
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::distanceSqr));
        if (candidates.isEmpty()) {
            return new SearchResult(null, outsideRestriction, false);
        }

        MaidPathFindingBFS pathFinding = new MaidPathFindingBFS(
                maid.getNavigation().getNodeEvaluator(), level, maid, center,
                maxDistance + 2.0f, VERTICAL_SEARCH_DISTANCE + 2);
        try {
            for (Candidate candidate : candidates) {
                BlockPos approach = findApproachPosition(maid, pathFinding, candidate.pos);
                if (approach != null) {
                    return new SearchResult(new Target(candidate.pos, approach, candidate.blockId,
                            candidate.edible), outsideRestriction, false);
                }
            }
        } finally {
            pathFinding.finish();
        }
        return new SearchResult(null, outsideRestriction, true);
    }

    private static BlockPos findApproachPosition(EntityMaid maid, MaidPathFindingBFS pathFinding,
                                                 BlockPos foodPos) {
        List<BlockPos> approaches = new ArrayList<>();
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    BlockPos approach = foodPos.offset(x, y, z);
                    if (maid.isWithinRestriction(approach)) {
                        approaches.add(approach);
                    }
                }
            }
        }
        approaches.sort(Comparator.comparingDouble(pos -> maid.distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos approach : approaches) {
            if (pathFinding.canPathReach(approach)) {
                return approach.immutable();
            }
        }
        return null;
    }

    private static boolean safeShouldMoveTo(IMaidEdibleBlock edible, EntityMaid maid, BlockPos pos,
                                            BlockState state) {
        try {
            return edible.shouldMoveTo(maid, pos, state);
        } catch (RuntimeException exception) {
            TouhouAIFun.LOGGER.warn("Edible-block provider {} rejected {} unexpectedly",
                    edible.getClass().getName(), pos, exception);
            return false;
        }
    }

    public static boolean matchesRequestedFood(String requestedFood, String registryId) {
        String requested = normalizeRequestedFood(requestedFood);
        if (requested.isBlank()) {
            return true;
        }
        String id = registryId == null ? "" : registryId.trim().toLowerCase(Locale.ROOT);
        if (id.equals(requested)) {
            return true;
        }
        int separator = id.indexOf(':');
        String path = separator >= 0 ? id.substring(separator + 1) : id;
        return path.equals(requested) || path.contains(requested);
    }

    static String normalizeRequestedFood(String requestedFood) {
        String value = requestedFood == null ? "" : requestedFood.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.equals("any") || value.equals("food") || value.equals("edible")
                || value.equals("任意") || value.equals("食物") || value.equals("零食")) {
            return "";
        }
        if (value.contains("蛋糕") || value.equals("cake")) {
            return "cake";
        }
        return value.replace(' ', '_');
    }

    private static String displayRequestedFood(String requestedFood) {
        String normalized = normalizeRequestedFood(requestedFood);
        return normalized.isBlank() ? "any" : normalized;
    }

    private record Candidate(BlockPos pos, String blockId, IMaidEdibleBlock edible,
                             double distanceSqr) {
    }

    private record Target(BlockPos foodPos, BlockPos approachPos, String blockId, IMaidEdibleBlock edible) {
    }

    private record SearchResult(Target target, boolean outsideRestriction, boolean matchingButUnreachable) {
    }

    public record Result(boolean success, String status, String detail, String blockId, BlockPos position) {
        static Result success(String blockId, BlockPos position) {
            return new Result(true, "success", "The maid reached and consumed the edible block.",
                    blockId, position);
        }

        static Result failure(String status, String detail) {
            return new Result(false, status, detail, "", null);
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("success", success);
            root.addProperty("status", status);
            root.addProperty("detail", detail);
            if (blockId != null && !blockId.isBlank()) {
                root.addProperty("block_id", blockId);
            }
            if (position != null) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", position.getX());
                pos.addProperty("y", position.getY());
                pos.addProperty("z", position.getZ());
                root.add("position", pos);
            }
            return root.toString();
        }
    }

    private static final class ActiveAction {
        private final EntityMaid maid;
        private final Object callback;
        private final Target target;
        private final CompletableFuture<Result> future;
        private final long deadline;

        private ActiveAction(EntityMaid maid, Object callback, Target target,
                             CompletableFuture<Result> future, long deadline) {
            this.maid = maid;
            this.callback = callback;
            this.target = target;
            this.future = future;
            this.deadline = deadline;
        }

        private void tick() {
            if (!(maid.level() instanceof ServerLevel level) || maid.isRemoved() || !maid.isAlive()) {
                finish(Result.failure("maid_unavailable", "The maid became unavailable."));
                return;
            }
            if (ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
                finish(Result.failure("superseded", "A newer user instruction cancelled this action."));
                return;
            }
            if (level.getGameTime() >= deadline) {
                finish(Result.failure("timeout", "The maid could not reach the edible block in time."));
                return;
            }
            if (maid.isSleeping() || maid.isPassenger() || maid.isLeashed()
                    || maid.isMaidInSittingPose()) {
                finish(Result.failure("movement_blocked", "The maid can no longer move to the edible block."));
                return;
            }

            BlockState current = level.getBlockState(target.foodPos);
            if (!safeShouldMoveTo(target.edible, maid, target.foodPos, current)) {
                finish(Result.failure("target_lost", "The target is no longer an edible block."));
                return;
            }

            if (maid.distanceToSqr(Vec3.atCenterOf(target.foodPos)) <= CONSUME_DISTANCE_SQR) {
                int points = target.edible.getFavorabilityPoints(maid, target.foodPos, current);
                if (target.edible.consume(maid, target.foodPos, current)) {
                    maid.getFavorabilityManager().apply(Type.STEAL_EDIBLE_BLOCK, points);
                    maid.swing(InteractionHand.MAIN_HAND);
                    finish(Result.success(target.blockId, target.foodPos));
                } else {
                    finish(Result.failure("consume_failed", "The edible block refused the consume action."));
                }
                return;
            }

            // The active work task may try to replace WALK_TARGET during the entity tick. This is
            // cheap when our target is still owned, and restores it immediately only when needed.
            takeControl();
        }

        /** Prevent ordinary work targets from winning while this explicit player action is active. */
        private void takeControl() {
            Brain<EntityMaid> brain = maid.getBrain();
            if (ownsWalkTarget(brain)) {
                brain.eraseMemory(InitEntities.TARGET_POS.get());
                brain.eraseMemory(InitEntities.MAID_EDIBLE_BLOCK_ACTION.get());
                return;
            }
            brain.eraseMemory(MemoryModuleType.PATH);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(InitEntities.TARGET_POS.get());
            brain.eraseMemory(InitEntities.MAID_EDIBLE_BLOCK_ACTION.get());
            BehaviorUtils.setWalkAndLookTargetMemories(maid, target.approachPos, WALK_SPEED, 0);
        }

        private void stopMoving() {
            if (maid.isRemoved()) {
                return;
            }
            Brain<EntityMaid> brain = maid.getBrain();
            if (!ownsWalkTarget(brain)) {
                return;
            }
            maid.getNavigation().stop();
            brain.eraseMemory(MemoryModuleType.PATH);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(InitEntities.TARGET_POS.get());
        }

        private boolean ownsWalkTarget(Brain<EntityMaid> brain) {
            return brain.getMemory(MemoryModuleType.WALK_TARGET)
                    .map(walkTarget -> target.approachPos.equals(
                            walkTarget.getTarget().currentBlockPosition()))
                    .orElse(false);
        }

        private void finish(Result result) {
            ACTIVE.remove(maid.getUUID(), this);
            stopMoving();
            complete(result);
        }

        private void complete(Result result) {
            future.complete(result);
        }
    }
}
