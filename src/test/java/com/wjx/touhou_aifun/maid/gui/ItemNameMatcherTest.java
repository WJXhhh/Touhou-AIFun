package com.wjx.touhou_aifun.maid.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNameMatcherTest {
    private static final List<ItemNameMatcher.Candidate> ITEMS = List.of(
            new ItemNameMatcher.Candidate("minecraft:brush", "刷子"),
            new ItemNameMatcher.Candidate("minecraft:bucket", "桶"),
            new ItemNameMatcher.Candidate("netherexp:brush", "灵魂刷子"),
            new ItemNameMatcher.Candidate("minecraft:iron_ingot", "铁锭"));

    @Test
    void chineseFuzzyPhraseResolvesTheRegisteredDisplayName() {
        List<ItemNameMatcher.Match> matches = ItemNameMatcher.rank(ITEMS, "软板的刷子", 8);
        assertFalseEmpty(matches);
        assertEquals("minecraft:brush", matches.get(0).candidate().id());
    }

    @Test
    void englishPhraseAndExactIdAlsoResolve() {
        assertEquals("minecraft:brush", ItemNameMatcher.rank(ITEMS, "soft brush", 8).get(0).candidate().id());
        assertEquals("minecraft:brush", ItemNameMatcher.rank(ITEMS, "minecraft:brush", 8).get(0).candidate().id());
        assertEquals("minecraft:iron_ingot", ItemNameMatcher.rank(ITEMS, "iron ingot", 8).get(0).candidate().id());
    }

    @Test
    void multipleBrushesStayAvailableForExplicitChoice() {
        List<ItemNameMatcher.Match> matches = ItemNameMatcher.rank(ITEMS, "刷子", 8);
        assertTrue(matches.size() >= 2);
        assertEquals("minecraft:brush", matches.get(0).candidate().id(),
                "The shorter exact display name should rank above a longer variant");
    }

    @Test
    void unrelatedPhraseAndBlankQueryReturnNothing() {
        assertTrue(ItemNameMatcher.rank(ITEMS, "钻石", 8).isEmpty());
        assertTrue(ItemNameMatcher.rank(ITEMS, "   ", 8).isEmpty());
        assertTrue(ItemNameMatcher.rank(ITEMS, "刷子", 0).isEmpty());
    }

    @Test
    void limitIsRespected() {
        assertEquals(2, ItemNameMatcher.rank(ITEMS, "刷子", 2).size());
    }

    @Test
    void normalizationDropsSeparatorsAndPunctuation() {
        assertEquals("minecraftbrush", ItemNameMatcher.normalize(" minecraft:brush "));
        assertEquals("软板的刷子", ItemNameMatcher.normalize("软板 的，刷子。"));
    }

    private static void assertFalseEmpty(List<ItemNameMatcher.Match> matches) {
        assertTrue(!matches.isEmpty());
    }
}
