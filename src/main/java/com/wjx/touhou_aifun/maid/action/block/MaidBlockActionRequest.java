package com.wjx.touhou_aifun.maid.action.block;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.Locale;

/** Validated argument object for one simulated block action. */
public record MaidBlockActionRequest(Action action, BlockPos target, @Nullable Direction face,
                                     @Nullable Direction facing, @Nullable String itemId,
                                     @Nullable String toolId, @Nullable String expected, boolean strict,
                                     int timeoutTicks) {
    public static final int DEFAULT_TIMEOUT_TICKS = 20 * 20;

    public enum Action { INTERACT, PLACE, MINE }

    public static MaidBlockActionRequest parse(JsonObject args, EntityMaid maid) {
        if (args == null) throw new IllegalArgumentException("invalid_arguments");
        Action action;
        try {
            action = Action.valueOf(required(args, "action").toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid_block_action");
        }
        int x = integer(args, "x");
        int y = integer(args, "y");
        int z = integer(args, "z");
        String space = optional(args, "coordinate_space", "world");
        BlockPos target;
        if (space.equals("world")) {
            target = new BlockPos(x, y, z);
        } else if (space.equals("maid_relative")) {
            target = maid.blockPosition().offset(x, y, z);
        } else {
            throw new IllegalArgumentException("invalid_coordinate_space");
        }
        Direction face = null;
        String rawFace = optional(args, "face", "");
        if (!rawFace.isBlank()) {
            try {
                face = Direction.byName(rawFace.toLowerCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid_face");
            }
            if (face == null) throw new IllegalArgumentException("invalid_face");
        }
        Direction facing = null;
        String rawFacing = optional(args, "facing", "");
        if (!rawFacing.isBlank()) {
            if (action != Action.PLACE) throw new IllegalArgumentException("facing_only_for_place");
            try {
                facing = Direction.byName(rawFacing.toLowerCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid_facing");
            }
            if (facing == null) throw new IllegalArgumentException("invalid_facing");
        }
        String item = optional(args, "item", "");
        String tool = optional(args, "tool", "");
        String expected = optional(args, "expected", "");
        if (!item.isBlank()) requireItem(item);
        if (!tool.isBlank()) requireItem(tool);
        int timeoutSeconds = intOrDefault(args, "timeout_seconds", 20, 1, 120);
        boolean strict = args.has("strict") && args.get("strict").isJsonPrimitive()
                && args.getAsJsonPrimitive("strict").isBoolean() && args.get("strict").getAsBoolean();
        return new MaidBlockActionRequest(action, target.immutable(), face, facing,
                item.isBlank() ? null : item.trim(),
                tool.isBlank() ? null : tool.trim(),
                expected.isBlank() ? null : expected.trim(),
                strict, timeoutSeconds * 20);
    }

    private static String required(JsonObject args, String key) {
        String value = optional(args, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + "_required");
        return value;
    }

    private static String optional(JsonObject args, String key, String fallback) {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonPrimitive()) return fallback;
        return args.get(key).getAsString();
    }

    private static int integer(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) throw new IllegalArgumentException(key + "_required");
        double value = args.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != Math.floor(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        return (int) value;
    }

    private static int intOrDefault(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        double value = args.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != Math.floor(value)) throw new IllegalArgumentException("invalid_" + key);
        int result = (int) value;
        if (result < min || result > max) throw new IllegalArgumentException("invalid_" + key);
        return result;
    }

    public static Item requireItem(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("unknown_item:" + id);
        return BuiltInRegistries.ITEM.get(key);
    }

    public static Block blockOf(Item item) {
        return item instanceof net.minecraft.world.item.BlockItem blockItem ? blockItem.getBlock() : null;
    }

    public boolean usesItem() {
        return action == Action.INTERACT || action == Action.PLACE;
    }

    public ItemStack expectedItemStack() {
        if (itemId == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
