package com.wjx.touhou_aifun.maid.action.block;

import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.gui.MaidGuiActor;
import com.wjx.touhou_aifun.mixin.BlockItemPlacementAccessor;
import com.wjx.touhou_aifun.mixin.StandingAndWallBlockItemAccessor;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic placement rotation for the simulated fake player. Vanilla placement derives block
 * orientation (stairs/chests/furnaces, pistons, logs, banners, trapdoors, wall attachments, ...)
 * from the player's view direction, so copying the maid's live Brain yaw made every placed block
 * face wherever her body happened to wander. This solver tests candidate rotations against the
 * block's own {@link net.minecraft.world.level.block.Block#getStateForPlacement} and prefers a
 * rotation whose predicted state satisfies the requested expected state. It never edits the world.
 */
public final class MaidPlacementOrientation {
    private static final int YAW_GRID_STEPS = 16;
    private static final float YAW_GRID_STEP = 360.0F / YAW_GRID_STEPS;

    private MaidPlacementOrientation() {
    }

    /** Chosen fake-player rotation plus why it was chosen and what it is predicted to place. */
    public record Result(float yaw, float pitch, String source, boolean satisfied,
                         @Nullable String requestedFacing, String predicted) {
        public Result withSatisfied(boolean value) {
            return new Result(yaw, pitch, source, value, requestedFacing, predicted);
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("yaw", round(yaw));
            out.addProperty("pitch", round(pitch));
            out.addProperty("source", source);
            out.addProperty("satisfied", satisfied);
            if (requestedFacing != null && !requestedFacing.isBlank()) {
                out.addProperty("requested_facing", requestedFacing);
            }
            if (predicted != null && !predicted.isBlank()) out.addProperty("predicted_block", predicted);
            return out;
        }

        private static float round(float value) {
            return Math.round(value * 10.0F) / 10.0F;
        }
    }

    private record Candidate(float yaw, float pitch, String source) {
    }

    private record Prediction(boolean matches, int score, @Nullable BlockState state) {
    }

    /**
     * Resolves the rotation the simulated actor should use for one placement. The actor is restored
     * to its previous rotation before returning; the caller applies the result with
     * {@link MaidGuiActor#orient(float, float)}. Never throws for exotic third-party blocks.
     */
    public static Result resolve(MaidGuiActor actor, InteractionHand hand, ItemStack stack,
                                 BlockHitResult hit, @Nullable MaidBlockStateMatcher.Expected expected,
                                 @Nullable Direction facing) {
        float originalYaw = actor.getYRot();
        float originalPitch = actor.getXRot();
        Candidate natural = natural(actor, hit);
        String requestedFacing = facing == null ? null : facing.getName();
        try {
            if (expected == null || expected.air() || stack == null || stack.isEmpty()
                    || !(stack.getItem() instanceof BlockItem)) {
                return new Result(natural.yaw(), natural.pitch(), natural.source(), true, requestedFacing, "");
            }
            if (expected.properties().isEmpty()) {
                Candidate chosen = explicitCandidate(facing);
                if (chosen == null) chosen = natural;
                return new Result(chosen.yaw(), chosen.pitch(), chosen.source(), true, requestedFacing, "");
            }
            Prediction best = null;
            Candidate bestCandidate = null;
            for (Candidate candidate : candidates(natural, expected, facing)) {
                Prediction prediction = predict(actor, hand, stack, hit, expected, candidate);
                if (prediction == null) continue;
                if (prediction.matches()) {
                    return new Result(candidate.yaw(), candidate.pitch(), candidate.source(), true,
                            requestedFacing, MaidBlockStateMatcher.serialize(prediction.state()));
                }
                if (best == null || prediction.score() > best.score()) {
                    best = prediction;
                    bestCandidate = candidate;
                }
            }
            Candidate chosen = bestCandidate == null ? natural : bestCandidate;
            return new Result(chosen.yaw(), chosen.pitch(), chosen.source(), false, requestedFacing,
                    best == null || best.state() == null ? "" : MaidBlockStateMatcher.serialize(best.state()));
        } finally {
            actor.setYRot(originalYaw);
            actor.setXRot(originalPitch);
            actor.yRotO = originalYaw;
            actor.xRotO = originalPitch;
        }
    }

    /** Vanilla player-like rotation: look at the exact point the action will click. */
    private static Candidate natural(MaidGuiActor actor, BlockHitResult hit) {
        Vec3 eye = actor.getEyePosition();
        Vec3 point = hit == null ? null : hit.getLocation();
        if (point == null) return new Candidate(actor.getYRot(), actor.getXRot(), "look_at_hit");
        Vec3 delta = point.subtract(eye);
        if (delta.lengthSqr() < 1.0E-6) return new Candidate(actor.getYRot(), actor.getXRot(), "look_at_hit");
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.atan2(-delta.x, delta.z) * (180.0 / Math.PI));
        float pitch = (float) (-Math.atan2(delta.y, horizontal) * (180.0 / Math.PI));
        return new Candidate(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F), "look_at_hit");
    }

    private static Candidate explicitCandidate(@Nullable Direction facing) {
        if (facing == null) return null;
        return lookCandidate(facing.getOpposite(), "facing");
    }

    private static Candidate lookCandidate(Direction look, String source) {
        if (look == null) return null;
        if (look.getAxis().isVertical()) {
            return new Candidate(0.0F, look == Direction.UP ? -90.0F : 90.0F, source);
        }
        return new Candidate(Mth.wrapDegrees(look.toYRot()), 0.0F, source);
    }

    private static List<Candidate> candidates(Candidate natural, MaidBlockStateMatcher.Expected expected,
                                              @Nullable Direction facing) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        add(candidates, seen, natural);
        add(candidates, seen, explicitCandidate(facing));
        add(candidates, seen, lookCandidate(expectedFacing(expected), "expected_facing"));
        for (int index = 0; index < YAW_GRID_STEPS; index++) {
            add(candidates, seen, new Candidate(Mth.wrapDegrees(index * YAW_GRID_STEP), 0.0F, "yaw_grid"));
        }
        add(candidates, seen, new Candidate(0.0F, -90.0F, "look_up"));
        add(candidates, seen, new Candidate(0.0F, 90.0F, "look_down"));
        return candidates;
    }

    private static void add(List<Candidate> candidates, Set<String> seen, @Nullable Candidate candidate) {
        if (candidate == null) return;
        String key = Math.round(Mth.wrapDegrees(candidate.yaw()) * 100.0F) + ":"
                + Math.round(candidate.pitch() * 100.0F);
        if (seen.add(key)) candidates.add(candidate);
    }

    @Nullable
    private static Direction expectedFacing(MaidBlockStateMatcher.Expected expected) {
        for (Map.Entry<String, String> entry : expected.properties().entrySet()) {
            if (!"facing".equalsIgnoreCase(entry.getKey())) continue;
            try {
                return Direction.byName(entry.getValue().toLowerCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        return null;
    }

    /**
     * True when the placement must not proceed unless the solver proved the expected wall variant:
     * variant items choose standing/wall by view, so a best-effort fallback could mutate the world
     * with the other variant.
     */
    public static boolean requiresVerifiedOrientation(ItemStack stack, @Nullable Block expectedBlock) {
        if (expectedBlock == null || stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        return item instanceof StandingAndWallBlockItem && item.getBlock() != expectedBlock;
    }

    /**
     * True when the held item can actually place this block: either its own block, or (for
     * signs/torches/skulls/coral fans) the wall variant of a standing-and-wall item.
     */
    public static boolean canProduce(ItemStack stack, @Nullable Block block) {
        if (block == null || stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        if (item.getBlock() == block) return true;
        if (item instanceof StandingAndWallBlockItem variant) {
            try {
                return ((StandingAndWallBlockItemAccessor) variant).touhouAIFun$wallBlock() == block;
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }
        return false;
    }

    @Nullable
    private static Prediction predict(MaidGuiActor actor, InteractionHand hand, ItemStack stack,
                                      BlockHitResult hit, MaidBlockStateMatcher.Expected expected,
                                      Candidate candidate) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        actor.setYRot(candidate.yaw());
        actor.setXRot(candidate.pitch());
        BlockState state;
        try {
            BlockPlaceContext context = new BlockPlaceContext(actor, hand, stack, hit);
            // Use the item's own placement state: variant items (signs, torches, skulls, coral
            // fans) choose a standing/wall block from the player's view, so the expected block's
            // getStateForPlacement alone can predict the wrong variant.
            state = ((BlockItemPlacementAccessor) (Object) item).touhouAIFun$getPlacementState(context);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
        if (state == null || state.getBlock() != expected.block()) return null;
        int required = expected.properties().size();
        int score = 0;
        for (Map.Entry<String, String> entry : expected.properties().entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) continue;
            if (propertyMatches(state, property, entry.getValue())) score++;
        }
        return new Prediction(score == required, score, state);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean propertyMatches(BlockState state, Property property, String raw) {
        Optional<? extends Comparable<?>> parsed = property.getValue(raw);
        if (parsed.isEmpty()) return false;
        return parsed.get().equals(state.getValue(property));
    }
}
