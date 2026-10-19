package com.wjx.touhou_aifun.maid.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/** Live registry adapter for {@link ItemNameMatcher}; the item registry is static after load. */
public final class RegisteredItemSearch {
    public record Match(ResourceLocation id, String displayName, int score) {
    }

    private static volatile List<ItemNameMatcher.Candidate> CACHE;

    private RegisteredItemSearch() {
    }

    /** Fuzzy registered item search, best match first. Returns an empty list for a blank query. */
    public static List<Match> search(String query, int limit) {
        List<ItemNameMatcher.Match> ranked = ItemNameMatcher.rank(candidates(), query, limit);
        List<Match> matches = new ArrayList<>(ranked.size());
        for (ItemNameMatcher.Match match : ranked) {
            ResourceLocation id = ResourceLocation.tryParse(match.candidate().id());
            if (id != null) {
                matches.add(new Match(id, match.candidate().displayName(), match.score()));
            }
        }
        return List.copyOf(matches);
    }

    private static List<ItemNameMatcher.Candidate> candidates() {
        List<ItemNameMatcher.Candidate> cached = CACHE;
        if (cached != null) {
            return cached;
        }
        List<ItemNameMatcher.Candidate> result = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                result.add(new ItemNameMatcher.Candidate(id.toString(), item.getDescription().getString()));
            }
        }
        cached = List.copyOf(result);
        CACHE = cached;
        return cached;
    }
}
