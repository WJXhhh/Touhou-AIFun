package com.wjx.touhou_aifun.maid.action.block;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.maid.action.MaidActionLease;
import com.wjx.touhou_aifun.maid.action.MaidBlockInteraction;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import com.wjx.touhou_aifun.maid.gui.MaidGuiActor;
import com.wjx.touhou_aifun.maid.gui.MaidGuiInventoryBinding;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-thread owner for one simulated block action. It only performs the physical action and a
 * deterministic post-action check; the build queue decides whether an ambiguous state needs the
 * native decision service or the execution LLM.
 */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidBlockActionManager {
    private static final float WALK_SPEED = 0.65f;
    private static final int REPLAN_TICKS = 20;
    /** Click-position candidates per support face, needed for half/slab/trapdoor orientation. */
    private static final int HIT_POINTS_PER_FACE = 6;
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private MaidBlockActionManager() {
    }

    public static CompletableFuture<MaidBlockActionResult> start(EntityMaid maid, Object callback,
                                                                 MaidBlockActionRequest request) {
        CompletableFuture<MaidBlockActionResult> future = new CompletableFuture<>();
        if (!(maid.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            future.complete(MaidBlockActionResult.failure(request.action(), "wrong_thread",
                    "The action must start on the server thread.", request.target(), request.expected(), ""));
            return future;
        }
        if (maid.isRemoved() || !maid.isAlive()) {
            future.complete(MaidBlockActionResult.failure(request.action(), "maid_unavailable",
                    "The maid is not available.", request.target(), request.expected(), ""));
            return future;
        }
        if (maid.isSleeping() || maid.isPassenger() || maid.isLeashed()) {
            future.complete(MaidBlockActionResult.failure(request.action(), "movement_blocked",
                    "The maid cannot walk to the target now.", request.target(), request.expected(), ""));
            return future;
        }
        if (MaidGuiSessionManager.session(maid.getUUID()) != null) {
            future.complete(MaidBlockActionResult.failure(request.action(), "gui_open_close_first",
                    "Close the maid's GUI before a physical block action.", request.target(), request.expected(), ""));
            return future;
        }
        if (!level.hasChunkAt(request.target()) || !maid.isWithinRestriction(request.target())) {
            future.complete(MaidBlockActionResult.failure(request.action(),
                    level.hasChunkAt(request.target()) ? "outside_restriction" : "unloaded",
                    "The target is not loaded or outside the maid's activity area.", request.target(),
                    request.expected(), ""));
            return future;
        }
        MaidEatFoodActionManager.cancelForMaid(maid.getUUID(), "block action started");
        Active previous = ACTIVE.remove(maid.getUUID());
        if (previous != null) previous.cancel("superseded", "A newer physical action replaced this one.");
        if (!MaidActionLease.acquire(maid.getUUID(), future)) {
            future.complete(MaidBlockActionResult.failure(request.action(), "action_busy",
                    "Another physical action is active.", request.target(), request.expected(), ""));
            return future;
        }
        Active active = new Active(maid, callback, request, future, level.getGameTime() + request.timeoutTicks());
        ACTIVE.put(maid.getUUID(), active);
        TouhouAIFun.LOGGER.debug("AIFun block action: maid={} action={} target={} start", maid.getUUID(), request.action(), request.target());
        try {
            if (maid.isMaidInSittingPose()) maid.setInSittingPose(false);
            if (!active.prepare()) {
                active.finish(active.result);
                return future;
            }
            active.tick();
        } catch (RuntimeException failed) {
            TouhouAIFun.LOGGER.error("Maid block action failed to start", failed);
            active.finish(MaidBlockActionResult.failure(request.action(), "internal_error",
                    "The block action failed unexpectedly.", request.target(), request.expected(), ""));
        }
        return future;
    }

    public static void cancelForMaid(UUID maidId, String reason) {
        Active active = ACTIVE.remove(maidId);
        if (active != null) active.cancel("cancelled", reason);
    }

    public static void clearAll() {
        List.copyOf(ACTIVE.values()).forEach(active -> active.cancel("cancelled", "The server stopped."));
        ACTIVE.clear();
    }

    public static boolean busy(UUID maidId) {
        return ACTIVE.containsKey(maidId);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        for (Map.Entry<UUID, Active> entry : List.copyOf(ACTIVE.entrySet())) {
            Active active = entry.getValue();
            if (active.future.isDone()) {
                active.cleanup();
                ACTIVE.remove(entry.getKey(), active);
                continue;
            }
            try {
                active.tick();
            } catch (Throwable failed) {
                TouhouAIFun.LOGGER.error("Maid block action tick failed", failed);
                active.finish(MaidBlockActionResult.failure(active.request.action(), "internal_error",
                        "The block action failed unexpectedly.", active.request.target(),
                        active.request.expected(), ""));
            }
        }
    }

    private static final class Active {
        private final EntityMaid maid;
        private final Object callback;
        private final MaidBlockActionRequest request;
        private final CompletableFuture<MaidBlockActionResult> future;
        private final long deadline;
        private MaidGuiActor actor;
        private MaidItemSelection.Source source;
        private MaidItemSelection.Borrow borrow;
        private MaidItemSelection.Source tool;
        private InteractionHand hand = InteractionHand.MAIN_HAND;
        private ItemStack handStack = ItemStack.EMPTY;
        private BlockPos support;
        private Direction face;
        private BlockHitResult hit;
        private BlockPos approach;
        private long nextPlanTick;
        private boolean acted;
        private long actedAt;
        private int mineTicks;
        private long mineDelayUntil;
        private MaidBlockActions.Outcome outcome;
        private BlockState previousState;
        @Nullable
        private MaidBlockStateMatcher.Expected placementExpected;
        private String expectedText = "";
        private String itemText = "";
        private MaidBlockActionResult result;

        Active(EntityMaid maid, Object callback, MaidBlockActionRequest request,
               CompletableFuture<MaidBlockActionResult> future, long deadline) {
            this.maid = maid;
            this.callback = callback;
            this.request = request;
            this.future = future;
            this.deadline = deadline;
        }

        boolean prepare() {
            ServerLevel level = (ServerLevel) maid.level();
            actor = MaidGuiInventoryBinding.construct(maid, () -> new MaidGuiActor(maid));
            actor.align();
            previousState = level.getBlockState(request.target());
            if (request.action() == MaidBlockActionRequest.Action.MINE) {
                if (previousState.isAir()) {
                    result = MaidBlockActionResult.success(request.action(), "already_satisfied",
                            request.target(), null, "minecraft:air",
                            MaidBlockStateMatcher.serialize(previousState), "", "", 0, false, List.of(), null);
                    return false;
                }
                if (request.toolId() != null) {
                    tool = MaidItemSelection.findItem(maid, request.toolId());
                    if (tool == null) {
                        result = failure("missing_tool", "The requested tool is not in the maid's inventory.",
                                "minecraft:air", MaidBlockStateMatcher.serialize(previousState));
                        return false;
                    }
                } else {
                    tool = bestTool(previousState);
                }
                expectedText = "minecraft:air";
                itemText = tool == null ? "" : MaidItemSelection.itemId(tool.stack());
                mineTicks = simulatedMineTicks(previousState, tool);
                if (!selectReach(request.target(), null)) return false;
                return true;
            }
            if (request.action() == MaidBlockActionRequest.Action.PLACE) {
                source = request.itemId() == null ? findPlaceableSource() : MaidItemSelection.findItem(maid, request.itemId());
                if (source == null) {
                    result = failure("missing_item", "No matching placeable item is available.", "", "");
                    return false;
                }
                if (!request.usesItem() || MaidBlockActions.isAir(previousState)
                        || previousState.canBeReplaced()) {
                    // Intended.
                }
                borrow = MaidItemSelection.borrow(maid, source);
                if (borrow == null) {
                    result = failure("missing_item", "The item could not be taken from the maid inventory.", "", "");
                    return false;
                }
                hand = borrow == null ? source.hand() : source.hand();
                handStack = maid.getItemInHand(hand);
                if (!(handStack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                    result = failure("item_is_not_placeable_block",
                            "The selected item cannot place a block.", "", "");
                    return false;
                }
                try {
                    placementExpected = MaidBlockActions.expectedFor(request, handStack);
                } catch (RuntimeException invalid) {
                    expectedText = request.expected() == null ? "" : request.expected();
                    result = failure(invalid.getMessage() == null ? "invalid_expected_block" : invalid.getMessage(),
                            "The expected block state cannot be used for this placement.", expectedText, "");
                    return false;
                }
                expectedText = MaidBlockStateMatcher.serialize(placementExpected);
                if (!MaidPlacementOrientation.canProduce(handStack, placementExpected.block())) {
                    result = failure("expected_block_item_mismatch",
                            "The held item cannot place the expected block.", expectedText, "");
                    return false;
                }
                itemText = MaidItemSelection.itemId(handStack);
                if (!selectPlacement()) return false;
                return true;
            }
            // Interact
            if (request.itemId() != null) {
                source = MaidItemSelection.findItem(maid, request.itemId());
                if (source == null) {
                    result = failure("missing_item", "The requested item is not available.", request.expected(), "");
                    return false;
                }
                borrow = MaidItemSelection.borrow(maid, source);
                if (borrow == null) {
                    result = failure("missing_item", "The item could not be taken from the maid inventory.",
                            request.expected(), "");
                    return false;
                }
                hand = borrow == null ? source.hand() : source.hand();
                handStack = maid.getItemInHand(hand);
                itemText = MaidItemSelection.itemId(handStack);
            } else {
                handStack = maid.getMainHandItem();
                hand = InteractionHand.MAIN_HAND;
                itemText = MaidItemSelection.itemId(handStack);
            }
            expectedText = request.expected() == null ? "" : request.expected();
            if (!selectReach(request.target(), request.face())) return false;
            return true;
        }

        private int simulatedMineTicks(BlockState state, @Nullable MaidItemSelection.Source toolSource) {
            try {
                if (!"simulated".equals(com.wjx.touhou_aifun.config.TouhouAIFunConfig.BLOCK_ACTION_MINING_MODE.get()))
                    return 0;
            } catch (RuntimeException unavailable) {
                return 0;
            }
            double hardness = Math.max(0.1, state.getDestroySpeed((ServerLevel) maid.level(), request.target()));
            double speed = toolSource == null ? 1.0 : Math.max(1.0, toolSource.stack().getDestroySpeed(state));
            int ticks = (int) Math.ceil(30.0 * hardness / speed);
            return Math.max(1, Math.min(40, ticks));
        }

        private boolean mineStillCharging(long now) {
            if (mineTicks <= 0) return false;
            if (mineDelayUntil == 0) {
                mineDelayUntil = now + mineTicks;
                maid.swing(InteractionHand.MAIN_HAND);
                takeControl();
                return true;
            }
            if (now >= mineDelayUntil) return false;
            if ((mineDelayUntil - now) % 5 == 0) maid.swing(InteractionHand.MAIN_HAND);
            takeControl();
            return true;
        }

        private MaidBlockActionResult failure(String error, String detail, String expected, String actual) {
            return MaidBlockActionResult.failure(request.action(), error, detail, request.target(),
                    expected, actual);
        }

        /**
         * Chooses a support face for placement. Besides reachability it prefers a face whose
         * predicted placement state satisfies the requested expected state, so orientation that
         * comes from the clicked face (wall attachments, log axis, slab/trapdoor half, ...) is
         * selected intentionally instead of by spawn direction. Falls back to the old behaviour.
         */
        private boolean selectPlacement() {
            List<Direction> order = new ArrayList<>();
            if (request.face() != null) order.add(request.face());
            for (Direction direction : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH,
                    Direction.EAST, Direction.WEST, Direction.DOWN)) {
                if (!order.contains(direction)) order.add(direction);
            }
            ServerLevel level = (ServerLevel) maid.level();
            MaidBlockStateMatcher.Expected expected = placementExpected;
            if (expected == null) {
                try {
                    expected = MaidBlockActions.expectedFor(request, handStack);
                    placementExpected = expected;
                } catch (RuntimeException invalid) {
                    result = failure(invalid.getMessage() == null ? "invalid_expected_block" : invalid.getMessage(),
                            "The expected block state cannot be used for this placement.", expectedText, "");
                    return false;
                }
            }
            Candidate matched = null;
            Candidate firstReachable = null;
            Candidate firstApproach = null;
            for (Direction candidate : order) {
                BlockPos candidateSupport = request.target().relative(candidate.getOpposite());
                if (!level.hasChunkAt(candidateSupport)) continue;
                BlockState state = level.getBlockState(candidateSupport);
                if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) continue;
                List<BlockHitResult> candidateHits;
                if (expected.properties().isEmpty()) {
                    BlockHitResult single = MaidBlockInteraction.hitFace(maid, candidateSupport, candidate);
                    candidateHits = single == null ? List.of() : List.of(single);
                } else {
                    candidateHits = MaidBlockInteraction.hitsOnFace(maid, candidateSupport, candidate,
                            HIT_POINTS_PER_FACE);
                }
                List<BlockHitResult> usable = new ArrayList<>();
                for (BlockHitResult candidateHit : candidateHits) {
                    // Refuse a support that vanilla would replace instead: clicking an existing
                    // slab/snow layer/candle with a matching item redirects the placement into the
                    // support block (double slab, taller snow) and mutates something we did not target.
                    try {
                        if (state.canBeReplaced(new net.minecraft.world.item.context.BlockPlaceContext(
                                actor, hand, handStack, candidateHit))) continue;
                    } catch (RuntimeException ignored) {
                    }
                    usable.add(candidateHit);
                }
                if (!usable.isEmpty()) {
                    if (matched == null && !expected.properties().isEmpty()) {
                        for (BlockHitResult candidateHit : usable) {
                            MaidPlacementOrientation.Result orientation = MaidPlacementOrientation.resolve(
                                    actor, hand, handStack, candidateHit, expected, request.facing());
                            if (orientation.satisfied()) {
                                matched = new Candidate(candidate, candidateSupport, candidateHit,
                                        maid.blockPosition());
                                break;
                            }
                        }
                    }
                    if (firstReachable == null) {
                        firstReachable = new Candidate(candidate, candidateSupport, usable.get(0),
                                maid.blockPosition());
                    }
                } else if (candidateHits.isEmpty() && firstApproach == null && matched == null) {
                    BlockPos candidateApproach = MaidBlockApproach.find(maid, candidateSupport, candidate, 16);
                    if (candidateApproach != null) {
                        firstApproach = new Candidate(candidate, candidateSupport, null, candidateApproach);
                    }
                }
            }
            Candidate chosen;
            if (matched != null) {
                chosen = matched;
            } else if (!expected.properties().isEmpty() && firstApproach != null) {
                // Orientation matters but no visible face satisfies it yet: walk to another
                // support instead of immediately placing the wrong orientation.
                chosen = firstApproach;
            } else {
                chosen = firstReachable != null ? firstReachable : firstApproach;
            }
            if (chosen == null) {
                result = failure("needs_support", "No reachable support face exists for this placement.",
                        expectedText, MaidBlockStateMatcher.serialize(level.getBlockState(request.target())));
                return false;
            }
            support = chosen.support();
            face = chosen.face();
            hit = chosen.hit();
            approach = chosen.approach();
            TouhouAIFun.LOGGER.debug("AIFun placement plan: target={} expected={} matched={} face={} hit={}",
                    request.target(), expectedText, matched != null, chosen.face(),
                    chosen.hit() == null ? "approach" : chosen.hit().getDirection() + "@" + chosen.hit().getLocation());
            return true;
        }

        private record Candidate(Direction face, BlockPos support, @Nullable BlockHitResult hit,
                                 BlockPos approach) {
        }

        private boolean selectReach(BlockPos target, @Nullable Direction preferredFace) {
            List<Direction> faces = new ArrayList<>();
            if (preferredFace != null) faces.add(preferredFace);
            for (Direction direction : Direction.values()) if (!faces.contains(direction)) faces.add(direction);
            for (Direction candidate : faces) {
                BlockHitResult candidateHit = preferredFace == null
                        ? MaidBlockInteraction.hit(maid, target)
                        : MaidBlockInteraction.hitFace(maid, target, candidate);
                if (candidateHit != null) {
                    hit = candidateHit;
                    face = candidateHit.getDirection();
                    approach = maid.blockPosition();
                    return true;
                }
                BlockPos candidateApproach = preferredFace == null
                        ? MaidBlockApproach.find(maid, target, null, 16)
                        : MaidBlockApproach.find(maid, target, candidate, 16);
                if (candidateApproach != null) {
                    hit = null;
                    face = preferredFace == null ? null : candidate;
                    approach = candidateApproach;
                    return true;
                }
            }
            result = failure("unreachable", "No reachable interaction position exists for the target.",
                    expectedText, "");
            return false;
        }

        @Nullable
        private MaidItemSelection.Source findPlaceableSource() {
            for (InteractionHand candidate : List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
                ItemStack stack = maid.getItemInHand(candidate);
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem
                        && matchesExpected(stack, previousState)) {
                    return new MaidItemSelection.Source(stack,
                            candidate == InteractionHand.MAIN_HAND ? MaidItemSelection.MAIN_SLOT : MaidItemSelection.OFFHAND_SLOT,
                            candidate);
                }
            }
            var backpack = maid.getAvailableBackpackInv();
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem && matchesExpected(stack, previousState)) {
                    return new MaidItemSelection.Source(stack, i, InteractionHand.MAIN_HAND);
                }
            }
            for (InteractionHand candidate : List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
                ItemStack stack = maid.getItemInHand(candidate);
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                    return new MaidItemSelection.Source(stack,
                            candidate == InteractionHand.MAIN_HAND ? MaidItemSelection.MAIN_SLOT : MaidItemSelection.OFFHAND_SLOT,
                            candidate);
                }
            }
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                    return new MaidItemSelection.Source(stack, i, InteractionHand.MAIN_HAND);
                }
            }
            return null;
        }

        private boolean matchesExpected(ItemStack stack, BlockState state) {
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem item)) return false;
            if (request.expected() != null) {
                try {
                    return MaidBlockStateMatcher.parse(request.expected()).block() == item.getBlock();
                } catch (RuntimeException invalid) {
                    return false;
                }
            }
            return !state.isAir() && state.getBlock() == item.getBlock();
        }

        @Nullable
        private MaidItemSelection.Source bestTool(BlockState state) {
            MaidItemSelection.Source best = null;
            double bestSpeed = -1;
            for (InteractionHand candidate : List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
                ItemStack stack = maid.getItemInHand(candidate);
                if (stack.isEmpty()) continue;
                double speed = stack.getDestroySpeed(state);
                if (stack.isCorrectToolForDrops(state)) speed += 100;
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    best = new MaidItemSelection.Source(stack,
                            candidate == InteractionHand.MAIN_HAND ? MaidItemSelection.MAIN_SLOT : MaidItemSelection.OFFHAND_SLOT,
                            candidate);
                }
            }
            var backpack = maid.getAvailableBackpackInv();
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.isEmpty()) continue;
                double speed = stack.getDestroySpeed(state);
                if (stack.isCorrectToolForDrops(state)) speed += 100;
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    best = new MaidItemSelection.Source(stack, i, InteractionHand.MAIN_HAND);
                }
            }
            return best;
        }

        void tick() {
            if (future.isDone()) return;
            if (!(maid.level() instanceof ServerLevel level) || maid.isRemoved() || !maid.isAlive()) {
                finish(failure("maid_unavailable", "The maid became unavailable.", expectedText, ""));
                return;
            }
            if (ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
                cancel("superseded", "A newer user instruction cancelled this action.");
                return;
            }
            if (level.getGameTime() >= deadline) {
                finish(failure("timeout", "The maid could not complete the block action in time.",
                        expectedText, MaidBlockStateMatcher.serialize(level.getBlockState(request.target()))));
                return;
            }
            if (maid.isSleeping() || maid.isPassenger() || maid.isLeashed()) {
                finish(failure("movement_blocked", "The maid can no longer reach the target.",
                        expectedText, MaidBlockStateMatcher.serialize(level.getBlockState(request.target()))));
                return;
            }
            if (acted) {
                if (level.getGameTime() <= actedAt) {
                    takeControl();
                    return;
                }
                verify();
                return;
            }
            if (hit == null) {
                if (!selectAgain(level)) return;
            }
            if (hit == null) {
                walk(level);
                return;
            }
            if (request.action() == MaidBlockActionRequest.Action.MINE && mineStillCharging(level.getGameTime()))
                return;
            stopMoving();
            actor.align();
            acted = true;
            actedAt = level.getGameTime();
            switch (request.action()) {
                case PLACE -> outcome = MaidBlockActions.place(maid, actor, request, request.target(),
                        support, face, hit, handStack, hand, borrow, true);
                case INTERACT -> outcome = MaidBlockActions.interact(maid, actor, hit, handStack, hand);
                case MINE -> {
                    ItemStack toolStack = tool == null ? ItemStack.EMPTY : tool.stack();
                    outcome = MaidBlockActions.mine(maid, actor, request.target(), toolStack);
                    if (outcome.success() && tool != null && !toolStack.isEmpty()
                            && toolStack.isCorrectToolForDrops(previousState)
                            && com.wjx.touhou_aifun.config.TouhouAIFunConfig.BLOCK_ACTION_SIMULATE_TOOL_DAMAGE.get()) {
                        MaidItemSelection.damageTool(maid, tool);
                    }
                }
            }
            if (outcome == null || !outcome.success()) {
                finish(resultFor(outcome));
                return;
            }
            takeControl();
        }

        private boolean selectAgain(ServerLevel level) {
            long now = level.getGameTime();
            if (now < nextPlanTick) return true;
            nextPlanTick = now + REPLAN_TICKS;
            if (request.action() == MaidBlockActionRequest.Action.PLACE) {
                if (!selectPlacement()) return false;
            } else if (!selectReach(request.target(), request.face())) {
                return false;
            }
            return true;
        }

        private void walk(ServerLevel level) {
            if (approach == null) {
                finish(failure("unreachable", "No reachable interaction position exists.", expectedText, ""));
                return;
            }
            if (maid.getNavigation().isDone()) {
                if (MaidBlockApproach.sameStandingColumn(maid, approach)
                        || maid.position().distanceToSqr(Vec3.atBottomCenterOf(approach)) <= .75 * .75) {
                    BlockPos revised = request.action() == MaidBlockActionRequest.Action.PLACE
                            ? MaidBlockApproach.find(maid, support, face, 16)
                            : MaidBlockApproach.find(maid, request.target(), face, 16);
                    if (revised == null) {
                        finish(failure("unreachable", "No unobstructed interaction position remains.",
                                expectedText, MaidBlockStateMatcher.serialize(level.getBlockState(request.target()))));
                        return;
                    }
                    approach = revised;
                }
                BlockPos target = approach;
                var path = maid.getNavigation().createPath(target, 0);
                if (path != null && path.getEndNode() != null) {
                    if (!maid.getNavigation().isInProgress()) maid.getNavigation().moveTo(path, WALK_SPEED);
                    maid.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
                }
            }
            takeControl();
        }

        private void verify() {
            ServerLevel level = (ServerLevel) maid.level();
            BlockState actual = level.getBlockState(request.target());
            if (outcome == null) {
                finish(failure("internal_error", "The block action produced no outcome.",
                        expectedText, MaidBlockStateMatcher.serialize(actual)));
                return;
            }
            if (outcome.menuOpened()) {
                finish(resultFor(outcome));
                return;
            }
            boolean satisfied;
            // Interact without an expected state is a one-shot interaction: the actual resulting
            // state is not predictable (levers/buttons/trapdoors), so it must not be compared to air.
            String expected = request.action() == MaidBlockActionRequest.Action.INTERACT && expectedText.isBlank()
                    ? "" : expectedText.isBlank() ? "minecraft:air" : expectedText;
            try {
                satisfied = switch (request.action()) {
                    case PLACE -> MaidBlockStateMatcher.matches(actual,
                            MaidBlockStateMatcher.parse(expected), request.strict());
                    case MINE -> actual.isAir();
                    case INTERACT -> expected.isBlank() || MaidBlockStateMatcher.matches(actual,
                            MaidBlockStateMatcher.parse(expected), request.strict());
                };
            } catch (RuntimeException invalid) {
                satisfied = false;
            }
            // Report the orientation as verified against the real block, not only as the solver's
            // prediction (post-placement connection/shape updates can differ from the prediction).
            MaidPlacementOrientation.Result orientation = outcome.orientation() == null
                    ? null : outcome.orientation().withSatisfied(satisfied);
            if (!satisfied) {
                finish(new MaidBlockActionResult(false, "verification_mismatch", "block_state_mismatch",
                        "The action completed but the expected state was not observed.",
                        request.action(), request.target(), face, expected, MaidBlockStateMatcher.serialize(actual),
                        MaidBlockStateMatcher.serialize(outcome.previous()), itemText, outcome.itemConsumed(),
                        1, false, outcome.drops(), outcome.snackCabinetType(), orientation));
                return;
            }
            finish(new MaidBlockActionResult(true, "completed", "", "", request.action(), request.target(),
                    face, expected, MaidBlockStateMatcher.serialize(actual),
                    MaidBlockStateMatcher.serialize(outcome.previous()), itemText, outcome.itemConsumed(), 1,
                    false, outcome.drops(), outcome.snackCabinetType(), orientation));
        }

        private MaidBlockActionResult resultFor(MaidBlockActions.Outcome outcome) {
            if (outcome == null) {
                return failure("internal_error", "The block action produced no outcome.", expectedText, "");
            }
            // No live verification happened on this path, so the prediction must not claim success.
            MaidPlacementOrientation.Result orientation = outcome.orientation() == null
                    ? null : outcome.orientation().withSatisfied(false);
            if (!outcome.success()) {
                ServerLevel level = (ServerLevel) maid.level();
                return new MaidBlockActionResult(false, "failed", outcome.error(), outcome.detail(),
                        request.action(), request.target(), face, expectedText,
                        MaidBlockStateMatcher.serialize(level.getBlockState(request.target())),
                        MaidBlockStateMatcher.serialize(outcome.previous()), itemText,
                        outcome.itemConsumed(), 1, false, outcome.drops(), outcome.snackCabinetType(), orientation);
            }
            return new MaidBlockActionResult(true, "completed", "", "", request.action(), request.target(),
                    face, expectedText, MaidBlockStateMatcher.serialize(outcome.actual()),
                    MaidBlockStateMatcher.serialize(outcome.previous()), itemText,
                    outcome.itemConsumed(), 1, false, outcome.drops(), outcome.snackCabinetType(), orientation);
        }

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
            if (approach != null) BehaviorUtils.setWalkAndLookTargetMemories(maid, approach, WALK_SPEED, 0);
        }

        private boolean ownsWalkTarget(Brain<EntityMaid> brain) {
            return approach != null && brain.getMemory(MemoryModuleType.WALK_TARGET)
                    .map(target -> approach.equals(target.getTarget().currentBlockPosition()))
                    .orElse(false);
        }

        private void stopMoving() {
            if (maid.isRemoved()) return;
            if (!ownsWalkTarget(maid.getBrain())) return;
            maid.getNavigation().stop();
            Brain<EntityMaid> brain = maid.getBrain();
            brain.eraseMemory(MemoryModuleType.PATH);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(InitEntities.TARGET_POS.get());
        }

        void cancel(String status, String reason) {
            finish(new MaidBlockActionResult(false, "failed", status, reason, request.action(),
                    request.target(), face, expectedText, "", "", itemText, 0, 1, false, List.of(), null, null));
        }

        void finish(MaidBlockActionResult value) {
            if (result == null) result = value;
            TouhouAIFun.LOGGER.debug("AIFun block action: maid={} action={} target={} status={} error={}",
                    maid.getUUID(), request.action(), request.target(), result.status(), result.error());
            ACTIVE.remove(maid.getUUID(), this);
            cleanup();
            future.complete(result);
        }

        void cleanup() {
            if (!maid.isRemoved()) stopMoving();
            MaidItemSelection.restore(maid, borrow);
            borrow = null;
            MaidActionLease.release(maid.getUUID(), future);
        }
    }
}
