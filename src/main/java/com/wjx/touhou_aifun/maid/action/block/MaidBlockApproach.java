package com.wjx.touhou_aifun.maid.action.block;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.wjx.touhou_aifun.maid.action.MaidBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;

import javax.annotation.Nullable;

/** Finds a standing position from which a specific target/face is reachable. */
public final class MaidBlockApproach {
    private static final int VERTICAL_SEARCH = 4;

    private MaidBlockApproach() {
    }

    @Nullable
    public static BlockPos find(EntityMaid maid, BlockPos target, @Nullable Direction face, int maxDistance) {
        if (MaidBlockInteraction.canInteractFrom(maid, target, maid.blockPosition())
                && (face == null ? MaidBlockInteraction.hit(maid, target) != null
                : MaidBlockInteraction.hitFace(maid, target, face) != null)) {
            return maid.blockPosition();
        }
        if (!(maid.level() instanceof ServerLevel level)) return null;
        int radius = Math.max(8, Math.min(64, maxDistance + 2));
        if (!loadedRegion(level, maid.blockPosition(), pathRadius(maid))) return null;
        MaidPathFindingBFS path = new MaidPathFindingBFS(maid.getNavigation().getNodeEvaluator(), level, maid,
                maid.blockPosition(), radius, VERTICAL_SEARCH + 2);
        try {
            return path.find(pos -> !pos.equals(target) && !sameStandingColumn(maid, pos)
                            && maid.isWithinRestriction(pos)
                            && (face == null ? MaidBlockInteraction.canInteractFrom(maid, target, pos)
                            : MaidBlockInteraction.canInteractFrom(maid, target, pos, face)))
                    .map(BlockPos::immutable).orElse(null);
        } finally {
            path.finish();
        }
    }

    public static int pathRadius(EntityMaid maid) {
        try {
            return (int) Math.ceil(maid.getAttributeValue(Attributes.FOLLOW_RANGE)) + 8;
        } catch (RuntimeException unavailable) {
            return 32;
        }
    }

    public static boolean loadedRegion(ServerLevel level, BlockPos center, int radius) {
        int minX = (center.getX() - radius) >> 4, maxX = (center.getX() + radius) >> 4;
        int minZ = (center.getZ() - radius) >> 4, maxZ = (center.getZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                if (!level.hasChunk(x, z)) return false;
        return true;
    }

    public static boolean sameStandingColumn(EntityMaid maid, BlockPos pos) {
        return pos.getX() == maid.blockPosition().getX() && pos.getZ() == maid.blockPosition().getZ()
                && Math.abs(pos.getY() - maid.getY()) <= 1.01;
    }
}
