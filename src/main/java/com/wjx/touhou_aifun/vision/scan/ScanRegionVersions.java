package com.wjx.touhou_aifun.vision.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Server-thread revisions cover block updates and sign edits without rescanning the volume. */
public final class ScanRegionVersions {
    private static final Map<Level,Region> REGIONS=new WeakHashMap<>();
    private static final class Region { long epoch, next; final Map<Long,Long> chunks=new HashMap<>(); }
    private ScanRegionVersions() { }
    public static void changed(Level level,BlockPos position) {
        if (level.isClientSide) return;
        Region region=REGIONS.computeIfAbsent(level,k->new Region());
        if (region.chunks.size()>=4096) { region.chunks.clear(); region.epoch++; }
        region.chunks.put(ChunkPos.asLong(position.getX()>>4,position.getZ()>>4),++region.next);
    }
    static String version(Level level,BlockPos position,int radius) {
        Region region=REGIONS.get(level);
        StringBuilder out=new StringBuilder().append(region==null?0:region.epoch).append(':');
        for(int x=(position.getX()-radius)>>4;x<=((position.getX()+radius)>>4);x++)
            for(int z=(position.getZ()-radius)>>4;z<=((position.getZ()+radius)>>4);z++) {
                out.append(level.hasChunk(x,z)?'L':'U').append(region==null?0:region.chunks.getOrDefault(ChunkPos.asLong(x,z),0L)).append(',');
            }
        return out.toString();
    }
}
