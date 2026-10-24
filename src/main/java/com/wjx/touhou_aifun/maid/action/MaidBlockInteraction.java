package com.wjx.touhou_aifun.maid.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Shared server-side reach/visibility rule for planning and performing block actions. */
public final class MaidBlockInteraction {
    private static final double EPSILON = 1.0e-5;
    private static final double[] FACE_SAMPLES = {0.5, 0.15, 0.85};
    /** Vertical click positions first, so half/top state candidates are not cut off by limits. */
    private static final double[] VERTICAL_SAMPLES = {0.5, 0.85, 0.15};

    private MaidBlockInteraction() { }

    public static BlockHitResult hit(EntityMaid maid, BlockPos target) {
        List<BlockHitResult> hits = hitsFromEyes(maid, target, maid.getEyePosition(), null, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** Real outline hit on one specific face; used for placing a block on an exact side. */
    public static BlockHitResult hitFace(EntityMaid maid, BlockPos target, Direction requiredFace) {
        if (requiredFace == null) return hit(maid, target);
        List<BlockHitResult> hits = hitsFromEyes(maid, target, maid.getEyePosition(), requiredFace, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * Up to {@code limit} distinct real outline hits on one required face, ordered from the
     * natural nearest point to sampled lower/upper points. Placement state such as slab/stairs/
     * trapdoor {@code half} depends on the click position, so the placement planner needs more
     * than one canonical hit point per face.
     */
    public static List<BlockHitResult> hitsOnFace(EntityMaid maid, BlockPos target, Direction requiredFace,
                                                  int limit) {
        if (requiredFace == null) {
            BlockHitResult value = hit(maid, target);
            return value == null ? List.of() : List.of(value);
        }
        return hitsFromEyes(maid, target, maid.getEyePosition(), requiredFace, Math.max(1, limit));
    }

    public static boolean canInteractFrom(EntityMaid maid, BlockPos target, BlockPos standing) {
        return canInteractFrom(maid, target, standing, null);
    }

    public static boolean canInteractFrom(EntityMaid maid, BlockPos target, BlockPos standing,
                                          Direction requiredFace) {
        // Navigation nodes use integral Y even on slabs/chests. Use their actual floor height.
        Vec3 eyes = new Vec3(standing.getX() + 0.5,
                WalkNodeEvaluator.getFloorLevel(maid.level(), standing) + maid.getEyeHeight(),
                standing.getZ() + 0.5);
        return !hitsFromEyes(maid, target, eyes, requiredFace, 1).isEmpty();
    }

    private static List<BlockHitResult> hitsFromEyes(EntityMaid maid, BlockPos target, Vec3 eyes,
                                                     Direction requiredFace, int limit) {
        var level = maid.level();
        if (!level.hasChunkAt(target)) return List.of();
        double reach = TouhouAIFunConfig.BLOCK_INTERACTION_REACH.get();
        double reachSqr = reach * reach;
        var shape = level.getBlockState(target).getShape(level, target, CollisionContext.of(maid));
        List<BlockHitResult> hits = new ArrayList<>();
        // Try the closest point on each face before sampling around partial obstructions.
        // The actual world ray must hit this block first; a synthetic centre hit is never used.
        for (AABB local : shape.toAabbs()) {
            AABB box = local.move(target);
            Vec3 closest = new Vec3(Mth.clamp(eyes.x, box.minX, box.maxX),
                    Mth.clamp(eyes.y, box.minY, box.maxY), Mth.clamp(eyes.z, box.minZ, box.maxZ));
            if (eyes.distanceToSqr(closest) > reachSqr) continue;
            for (Direction face : Direction.values()) {
                if (requiredFace != null && face != requiredFace) continue;
                Vec3 point = onFace(box, face, closest.x, closest.y, closest.z);
                addHit(hits, trace(maid, target, eyes, point, reachSqr, requiredFace), limit);
                if (hits.size() >= limit) return hits;
            }
            for (Direction face : Direction.values()) {
                if (requiredFace != null && face != requiredFace) continue;
                for (double vertical : VERTICAL_SAMPLES) for (double horizontal : FACE_SAMPLES) {
                    double u = face.getAxis() == Direction.Axis.X ? vertical : horizontal;
                    double v = face.getAxis() == Direction.Axis.X ? horizontal : vertical;
                    double x = Mth.lerp(u, box.minX, box.maxX);
                    double y = Mth.lerp(face.getAxis() == Direction.Axis.X ? u : v, box.minY, box.maxY);
                    double z = Mth.lerp(v, box.minZ, box.maxZ);
                    addHit(hits, trace(maid, target, eyes, onFace(box, face, x, y, z), reachSqr, requiredFace), limit);
                    if (hits.size() >= limit) return hits;
                }
            }
        }
        return hits;
    }

    private static void addHit(List<BlockHitResult> hits, @Nullable BlockHitResult hit, int limit) {
        if (hit == null || hits.size() >= limit) return;
        for (BlockHitResult existing : hits) {
            if (existing.getDirection() == hit.getDirection()
                    && existing.getLocation().distanceToSqr(hit.getLocation()) < 1.0E-6) return;
        }
        hits.add(hit);
    }

    private static Vec3 onFace(AABB box, Direction face, double x, double y, double z) {
        return switch (face) {
            case DOWN -> new Vec3(x, box.minY, z);
            case UP -> new Vec3(x, box.maxY, z);
            case NORTH -> new Vec3(x, y, box.minZ);
            case SOUTH -> new Vec3(x, y, box.maxZ);
            case WEST -> new Vec3(box.minX, y, z);
            case EAST -> new Vec3(box.maxX, y, z);
        };
    }

    private static BlockHitResult trace(EntityMaid maid, BlockPos target, Vec3 eyes, Vec3 point, double reachSqr,
                                        @Nullable Direction requiredFace) {
        Vec3 ray = point.subtract(eyes);
        if (ray.lengthSqr() < EPSILON * EPSILON || ray.lengthSqr() > reachSqr + EPSILON) return null;
        Vec3 end = point.add(ray.normalize().scale(EPSILON));
        // Never request unloaded chunks, including those crossed by a short interaction ray.
        BlockPos startBlock = BlockPos.containing(eyes), endBlock = BlockPos.containing(end);
        for (int x = Math.min(startBlock.getX(), endBlock.getX()) >> 4; x <= (Math.max(startBlock.getX(), endBlock.getX()) >> 4); x++)
            for (int z = Math.min(startBlock.getZ(), endBlock.getZ()) >> 4; z <= (Math.max(startBlock.getZ(), endBlock.getZ()) >> 4); z++)
                if (!maid.level().hasChunk(x, z)) return null;
        BlockHitResult hit = maid.level().clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, maid));
        // Exact face matters: placement orientation (wall attachments, log axis, half) and
        // face-specific interactions must click the face they planned, not a corner/adjacent face.
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) && !hit.isInside()
                && (requiredFace == null || hit.getDirection() == requiredFace)
                && eyes.distanceToSqr(hit.getLocation()) <= reachSqr + EPSILON ? hit : null;
    }
}
