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

/** Shared server-side reach/visibility rule for planning and performing block actions. */
public final class MaidBlockInteraction {
    private static final double EPSILON = 1.0e-5;
    private static final double[] FACE_SAMPLES = {0.5, 0.15, 0.85};

    private MaidBlockInteraction() { }

    public static BlockHitResult hit(EntityMaid maid, BlockPos target) {
        return hitFromEyes(maid, target, maid.getEyePosition());
    }

    public static boolean canInteractFrom(EntityMaid maid, BlockPos target, BlockPos standing) {
        // Navigation nodes use integral Y even on slabs/chests. Use their actual floor height.
        Vec3 eyes = new Vec3(standing.getX() + 0.5,
                WalkNodeEvaluator.getFloorLevel(maid.level(), standing) + maid.getEyeHeight(),
                standing.getZ() + 0.5);
        return hitFromEyes(maid, target, eyes) != null;
    }

    private static BlockHitResult hitFromEyes(EntityMaid maid, BlockPos target, Vec3 eyes) {
        var level = maid.level();
        if (!level.hasChunkAt(target)) return null;
        double reach = TouhouAIFunConfig.BLOCK_INTERACTION_REACH.get();
        double reachSqr = reach * reach;
        var shape = level.getBlockState(target).getShape(level, target, CollisionContext.of(maid));
        // Try the closest point on each face before sampling around partial obstructions.
        // The actual world ray must hit this block first; a synthetic centre hit is never used.
        for (AABB local : shape.toAabbs()) {
            AABB box = local.move(target);
            Vec3 closest = new Vec3(Mth.clamp(eyes.x, box.minX, box.maxX),
                    Mth.clamp(eyes.y, box.minY, box.maxY), Mth.clamp(eyes.z, box.minZ, box.maxZ));
            if (eyes.distanceToSqr(closest) > reachSqr) continue;
            for (Direction face : Direction.values()) {
                Vec3 point = onFace(box, face, closest.x, closest.y, closest.z);
                BlockHitResult hit = trace(maid, target, eyes, point, reachSqr);
                if (hit != null) return hit;
            }
            for (Direction face : Direction.values()) {
                for (double u : FACE_SAMPLES) for (double v : FACE_SAMPLES) {
                    double x = Mth.lerp(u, box.minX, box.maxX);
                    double y = Mth.lerp(face.getAxis() == Direction.Axis.X ? u : v, box.minY, box.maxY);
                    double z = Mth.lerp(v, box.minZ, box.maxZ);
                    BlockHitResult hit = trace(maid, target, eyes, onFace(box, face, x, y, z), reachSqr);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
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

    private static BlockHitResult trace(EntityMaid maid, BlockPos target, Vec3 eyes, Vec3 point, double reachSqr) {
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
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) && !hit.isInside()
                && eyes.distanceToSqr(hit.getLocation()) <= reachSqr + EPSILON ? hit : null;
    }
}
