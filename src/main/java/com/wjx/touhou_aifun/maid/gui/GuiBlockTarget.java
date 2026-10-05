package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/** Explicit coordinate semantics; never silently reinterpret a world position as an offset. */
public final class GuiBlockTarget {
    private static final java.util.regex.Pattern QUALIFIED = java.util.regex.Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)@([+-]?\\d+),([+-]?\\d+),([+-]?\\d+)");
    private GuiBlockTarget() { }

    public static BlockPos coordinates(JsonObject request, BlockPos maidPosition) {
        String target=request.has("target")?request.get("target").getAsString():"";
        if(target.contains("@")) {
            var match=QUALIFIED.matcher(target);if(!match.matches()) throw new IllegalArgumentException("invalid_qualified_target_use_registry_id_at_xyz_or_separate_xyz");
            JsonObject normalized=request.deepCopy();String[] keys={"x","y","z"};
            boolean any=request.has("x") || request.has("y") || request.has("z");
            try {
                for(int i=0;i<3;i++) {
                    int coordinate=Integer.parseInt(match.group(i+2));
                    if(any && (!request.has(keys[i]) || request.get(keys[i]).getAsBigDecimal().intValueExact()!=coordinate))
                        throw new IllegalArgumentException("conflicting_block_coordinates");
                    normalized.addProperty(keys[i],coordinate);
                }
            } catch(RuntimeException failure) { throw new IllegalArgumentException("invalid_or_conflicting_block_coordinates"); }
            normalized.remove("target");return coordinates(normalized,maidPosition);
        }
        boolean any = request.has("x") || request.has("y") || request.has("z");
        String space = request.has("coordinate_space") ? request.get("coordinate_space").getAsString() : "world";
        if (!space.equals("world") && !space.equals("maid_relative"))
            throw new IllegalArgumentException("invalid_coordinate_space");
        if (!any) {
            if (!space.equals("world")) throw new IllegalArgumentException("missing_block_coordinates");
            return null;
        }
        if (!request.has("x") || !request.has("y") || !request.has("z"))
            throw new IllegalArgumentException("requires_all_block_coordinates");
        try {
            int x = request.get("x").getAsBigDecimal().intValueExact();
            int y = request.get("y").getAsBigDecimal().intValueExact();
            int z = request.get("z").getAsBigDecimal().intValueExact();
            if (space.equals("maid_relative")) {
                x = Math.addExact(maidPosition.getX(), x);
                y = Math.addExact(maidPosition.getY(), y);
                z = Math.addExact(maidPosition.getZ(), z);
            }
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000)
                throw new ArithmeticException("outside world bounds");
            return new BlockPos(x, y, z);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid_block_coordinates");
        }
    }
    public static String qualifiedRegistry(JsonObject request) {
        if(!request.has("target")) return null;
        var match=QUALIFIED.matcher(request.get("target").getAsString());return match.matches()?match.group(1):null;
    }

    public static boolean matches(String wanted, String actual) {
        var qualified=QUALIFIED.matcher(wanted);if(qualified.matches()) wanted=qualified.group(1);
        return wanted.equals("nearest") || wanted.equals("any")
                || (wanted.contains(":") ? actual.equals(wanted) : actual.contains(wanted));
    }
}
