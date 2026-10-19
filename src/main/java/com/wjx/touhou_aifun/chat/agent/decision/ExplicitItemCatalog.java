package com.wjx.touhou_aifun.chat.agent.decision;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import java.util.*;

/** Literal names identify possible items, never prove that a closed container contains them. */
public final class ExplicitItemCatalog {
    public record NamedItem(String id, String displayName, int maxStack) { }
    private static final Map<String,List<String>> ALIASES=Map.ofEntries(
            Map.entry("iron_ingot",List.of("铁锭")),Map.entry("gold_ingot",List.of("金锭")),Map.entry("copper_ingot",List.of("铜锭")),
            Map.entry("diamond",List.of("钻石")),Map.entry("emerald",List.of("绿宝石")),Map.entry("egg",List.of("鸡蛋")),
            Map.entry("beef",List.of("生牛肉")),Map.entry("cooked_beef",List.of("熟牛肉","牛排")),Map.entry("iron_block",List.of("铁块")),
            Map.entry("gold_block",List.of("金块")),Map.entry("coal",List.of("煤炭")),Map.entry("charcoal",List.of("木炭")),
            Map.entry("redstone",List.of("红石粉")),Map.entry("lapis_lazuli",List.of("青金石")),Map.entry("wheat",List.of("小麦")),
            Map.entry("bread",List.of("面包")),Map.entry("apple",List.of("苹果")),Map.entry("carrot",List.of("胡萝卜")),Map.entry("potato",List.of("马铃薯","土豆")));
    private ExplicitItemCatalog() { }
    public static Map<String,Integer> named(String text) {
        String normalized=text.toLowerCase(Locale.ROOT);var out=new TreeMap<String,Integer>();
        for(Item item:BuiltInRegistries.ITEM) {
            var id=BuiltInRegistries.ITEM.getKey(item);String name=item.getDescription().getString().toLowerCase(Locale.ROOT);
            int nameCodePoints=name.codePointCount(0,name.length());
            boolean matched=normalized.contains(id.toString()) || !name.equals(item.getDescriptionId()) && nameCodePoints>=2 && normalized.contains(name);
            if(id.getNamespace().equals("minecraft"))matched|=ALIASES.getOrDefault(id.getPath(),List.of()).stream().anyMatch(text::contains);
            if(matched)out.put(id.toString(),item.getMaxStackSize());
        }
        return out;
    }

    /** Bounded registry description of the item names literally mentioned in the goal/constraints. */
    public static List<NamedItem> namedItems(String text, int limit) {
        if (text == null || text.isBlank() || limit <= 0) {
            return List.of();
        }
        List<NamedItem> out = new ArrayList<>();
        for (String id : named(text).keySet()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            Item item = key == null ? null : BuiltInRegistries.ITEM.get(key);
            if (item == null) {
                continue;
            }
            out.add(new NamedItem(id, item.getDescription().getString(), item.getMaxStackSize()));
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }
}
