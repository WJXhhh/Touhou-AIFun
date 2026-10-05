package com.wjx.touhou_aifun.vision.scan;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.ArrayList;
import java.util.List;

/** Called on the server thread, only after a sign position has passed the scan visibility rules. */
final class SignTextReader {
    private SignTextReader() { }

    static ScannedSign read(SignBlockEntity sign, String registryId, int dx, int dy, int dz,
                            double distance, String direction, boolean filtered) {
        return new ScannedSign(registryId, dx, dy, dz, distance, direction,
                lines(sign.getFrontText(), filtered), lines(sign.getBackText(), filtered), false,attachedBlock(sign));
    }
    private static ScannedSign.AttachedBlock attachedBlock(SignBlockEntity sign) {
        var state=sign.getBlockState();net.minecraft.core.Direction facing;
        if(state.getBlock() instanceof net.minecraft.world.level.block.WallSignBlock)
            facing=state.getValue(net.minecraft.world.level.block.WallSignBlock.FACING);
        else if(state.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock)
            facing=state.getValue(net.minecraft.world.level.block.WallHangingSignBlock.FACING);
        else return null;
        var position=sign.getBlockPos().relative(facing.getOpposite());var level=sign.getLevel();
        if(level==null || !level.hasChunkAt(position)) return null;
        String registry=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).toString();
        return new ScannedSign.AttachedBlock(registry,position.getX(),position.getY(),position.getZ());
    }

    private static List<String> lines(SignText text, boolean filtered) {
        List<String> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) lines.add(text.getMessage(i, filtered).getString());
        return lines;
    }
}
