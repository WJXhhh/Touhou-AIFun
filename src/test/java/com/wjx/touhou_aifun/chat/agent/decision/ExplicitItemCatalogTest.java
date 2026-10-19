package com.wjx.touhou_aifun.chat.agent.decision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitItemCatalogTest {
    @Test
    void namedItemsAreBoundedAndKeepRegistryIds() {
        // This only checks the public result shape without requiring a bootstrapped registry:
        // an empty/blank phrase and a negative limit must stay safe.
        assertTrue(ExplicitItemCatalog.namedItems("", 12).isEmpty());
        assertTrue(ExplicitItemCatalog.namedItems(null, 12).isEmpty());
        assertTrue(ExplicitItemCatalog.namedItems("anything", 0).isEmpty());
    }
}
