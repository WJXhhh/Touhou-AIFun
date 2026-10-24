package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskAnnouncementsOwnerQuestionTest {
    private static JsonObject question() {
        JsonObject missing = new JsonObject();
        missing.addProperty("item", "minecraft:cobblestone");
        missing.addProperty("display_name", "圆石");
        missing.addProperty("required", 64);
        missing.addProperty("have", 3);
        JsonArray items = new JsonArray();
        items.add(missing);
        JsonObject facts = new JsonObject();
        facts.add("missing_items", items);
        JsonObject question = new JsonObject();
        question.addProperty("kind", "materials");
        question.add("facts", facts);
        return question;
    }

    @Test void materialFallbackNamesItemsAndCountsInBothLanguages() {
        String zh = TaskAnnouncements.ownerQuestion(question(), false);
        assertTrue(zh.contains("圆石"));
        assertTrue(zh.contains("64"));
        assertTrue(zh.contains("自己去找"));
        String en = TaskAnnouncements.ownerQuestion(question(), true);
        assertTrue(en.contains("cobblestone") || en.contains("圆石"));
        assertTrue(en.contains("64"));
        assertTrue(en.toLowerCase().contains("find"));
    }

    @Test void genericFallbackNeverInventsAnItem() {
        JsonObject question = new JsonObject();
        question.addProperty("kind", "blocked");
        String zh = TaskAnnouncements.ownerQuestion(question, false);
        assertTrue(zh.contains("需要你决定") || zh.contains("一些材料"));
        String en = TaskAnnouncements.ownerQuestion(question, true);
        assertTrue(en.contains("decision") || en.contains("something"));
    }
}
