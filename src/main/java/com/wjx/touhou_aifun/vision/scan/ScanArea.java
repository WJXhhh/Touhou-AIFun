package com.wjx.touhou_aifun.vision.scan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/** Inclusive, deliberately small voxel region. Coordinates never depend on the maid's yaw. */
public record ScanArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String coordinateSpace) {
    public static final Codec<ScanArea> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("min_x").forGetter(ScanArea::minX),
            Codec.INT.fieldOf("min_y").forGetter(ScanArea::minY),
            Codec.INT.fieldOf("min_z").forGetter(ScanArea::minZ),
            Codec.INT.fieldOf("max_x").forGetter(ScanArea::maxX),
            Codec.INT.fieldOf("max_y").forGetter(ScanArea::maxY),
            Codec.INT.fieldOf("max_z").forGetter(ScanArea::maxZ),
            Codec.STRING.optionalFieldOf("coordinate_space", "maid_relative").forGetter(ScanArea::coordinateSpace)
    ).apply(instance, ScanArea::new));

    public ScanArea {
        if (!"maid_relative".equals(coordinateSpace) && !"world".equals(coordinateSpace)) {
            throw new IllegalArgumentException("invalid_area_coordinate_space");
        }
        if (!validAxis(minX, maxX) || !validAxis(minY, maxY) || !validAxis(minZ, maxZ)) {
            throw new IllegalArgumentException("scan_area_must_have_ordered_bounds_and_at_most_7_blocks_per_axis");
        }
    }

    private static boolean validAxis(int min, int max) {
        return min >= -30_000_000 && max <= 30_000_000 && (long) max - min >= 0 && (long) max - min < 7;
    }

    public int volume() { return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }

    public BlockPos position(int index, BlockPos origin) {
        int depth = maxZ - minZ + 1;
        int height = maxY - minY + 1;
        BlockPos pos = new BlockPos(minX + index / (depth * height),
                minY + index / depth % height, minZ + index % depth);
        return "world".equals(coordinateSpace) ? pos : origin.offset(pos);
    }
}
