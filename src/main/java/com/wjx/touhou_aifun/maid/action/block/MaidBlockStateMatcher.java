package com.wjx.touhou_aifun.maid.action.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Server-side parser/matcher for the block-state strings used by build designs and block actions.
 * It intentionally compares only the properties that were explicitly requested; strict mode still
 * only means "all requested properties must match", never "the actual state may have no others".
 */
public final class MaidBlockStateMatcher {
    private MaidBlockStateMatcher() {
    }

    /** air=true means "the position must be air"; properties are ignored in that case. */
    public record Expected(Block block, boolean air, Map<String, String> properties) {
        public Expected {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public static Expected parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("expected_block_required");
        String text = value.trim();
        String id = text;
        Map<String, String> properties = new LinkedHashMap<>();
        int bracket = text.indexOf('[');
        if (bracket >= 0) {
            if (!text.endsWith("]")) throw new IllegalArgumentException("invalid_block_state");
            id = text.substring(0, bracket).trim();
            String body = text.substring(bracket + 1, text.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String part : body.split(",")) {
                    int equals = part.indexOf('=');
                    if (equals <= 0) throw new IllegalArgumentException("invalid_block_state_property");
                    String name = part.substring(0, equals).trim();
                    String raw = part.substring(equals + 1).trim();
                    if (name.isBlank() || raw.isBlank()) throw new IllegalArgumentException("invalid_block_state_property");
                    if (properties.put(name, raw) != null) throw new IllegalArgumentException("duplicate_block_state_property");
                }
            }
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        java.util.Optional<Block> found = key == null ? java.util.Optional.empty()
                : BuiltInRegistries.BLOCK.getOptional(key);
        if (found.isEmpty()) throw new IllegalArgumentException("unknown_block:" + id);
        Block block = found.get();
        if (!properties.isEmpty() && block == net.minecraft.world.level.block.Blocks.AIR) {
            throw new IllegalArgumentException("air_has_no_properties");
        }
        Map<String, String> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("unknown_block_property:" + entry.getKey());
            String canonicalText = canonicalValue(property, entry.getValue());
            if (canonicalText == null) {
                throw new IllegalArgumentException("invalid_block_property_value:"
                        + entry.getKey() + "=" + entry.getValue());
            }
            canonical.put(entry.getKey(), canonicalText);
        }
        return new Expected(block, block == net.minecraft.world.level.block.Blocks.AIR, canonical);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private static String canonicalValue(Property property, String raw) {
        Optional<? extends Comparable<?>> parsed = property.getValue(raw);
        return parsed.map(value -> property.getName((Comparable) value)).orElse(null);
    }

    public static Expected fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            throw new IllegalArgumentException("item_is_not_placeable_block");
        }
        return new Expected(item.getBlock(), false, Map.of());
    }

    public static Expected air() {
        return new Expected(net.minecraft.world.level.block.Blocks.AIR, true, Map.of());
    }

    public static boolean matches(@Nullable BlockState actual, Expected expected, boolean strict) {
        if (actual == null) return false;
        if (expected.air()) return actual.isAir();
        if (!actual.is(expected.block())) return false;
        for (Map.Entry<String, String> entry : expected.properties().entrySet()) {
            Property<?> property = actual.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) return false;
            Optional<? extends Comparable<?>> parsed = property.getValue(entry.getValue());
            if (parsed.isEmpty()) return false;
            if (!parsed.get().equals(actual.getValue(property))) return false;
        }
        // strict is kept as an explicit contract flag for future value tolerances. A requested
        // property is always required; unrequested properties are not compared in either mode.
        return true;
    }

    public static String serialize(@Nullable BlockState state) {
        if (state == null) return "unknown";
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        StringBuilder out = new StringBuilder(key == null ? "unknown" : key.toString());
        Map<String, String> properties = properties(state);
        if (!properties.isEmpty()) {
            out.append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            out.append(']');
        }
        return out.toString();
    }

    /** Serializes a parsed expected state; properties are sorted so log/JSON output stays stable. */
    public static String serialize(@Nullable Expected expected) {
        if (expected == null) return "";
        if (expected.air()) return "minecraft:air";
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(expected.block());
        StringBuilder out = new StringBuilder(key == null ? "unknown" : key.toString());
        Map<String, String> properties = new TreeMap<>(expected.properties());
        if (!properties.isEmpty()) {
            out.append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            out.append(']');
        }
        return out.toString();
    }

    public static Map<String, String> properties(@Nullable BlockState state) {
        if (state == null) return Map.of();
        Map<String, String> values = new TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            values.put(property.getName(), state.getValue(property).toString());
        }
        return values;
    }

    public static String blockId(@Nullable BlockState state) {
        if (state == null) return "";
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "" : key.toString();
    }
}
