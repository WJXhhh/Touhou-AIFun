package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTSearchSourcesTest {
    @Test void lateActualCitationWinsOverALongListOfBrowsedPages() {
        var sources = new LinkedHashMap<String, ChatGPTSearchSources.Source>();
        JsonObject search = new JsonObject(); search.addProperty("type", "web_search_call");
        JsonObject action = new JsonObject(); var pages = new com.google.gson.JsonArray();
        for (int i = 0; i < 64; i++) {
            JsonObject page = new JsonObject(); page.addProperty("type", "url");
            page.addProperty("url", "https://example.com/page-" + i); pages.add(page);
        }
        action.add("sources", pages); search.add("action", action);
        ChatGPTSearchSources.collectItem(search, sources);
        JsonObject citation = new JsonObject(); citation.addProperty("type", "url_citation");
        citation.addProperty("url", "https://example.com/actual-evidence"); citation.addProperty("title", "Actual evidence");
        ChatGPTSearchSources.collectAnnotation(citation, sources);
        var visible = ChatGPTSearchSources.displaySources(sources);
        assertEquals(1, visible.size());
        assertEquals("Actual evidence", visible.get(0).title());
        assertTrue(visible.get(0).cited());
        assertTrue(sources.size() <= 64);
    }

    @Test void uncitedFallbackIsSmallAndDoesNotEraseUserFacingLinks() {
        var sources = new LinkedHashMap<String, ChatGPTSearchSources.Source>();
        for (int i = 0; i < 8; i++) sources.put("p" + i,
                new ChatGPTSearchSources.Source("Page " + i, "https://example.com/" + i, false));
        assertEquals(3, ChatGPTSearchSources.displaySources(sources).size());
        var reply = JsonParser.parseString("{\"output_text\":\"[Download](https://example.com/0)\"}").getAsJsonObject();
        ChatGPTSearchSources.cleanCitationText(reply, ChatGPTSearchSources.displaySources(sources));
        assertEquals("[Download](https://example.com/0)", reply.get("output_text").getAsString());
    }

    @Test void sourceListKeepsRealtimeWeatherWithoutInventingALink() {
        var item = JsonParser.parseString("""
                {"type":"web_search_call","action":{"type":"search","sources":[
                {"type":"api","name":"oai-weather"},
                {"type":"url","url":"https://weather.example/forecast"},
                {"type":"api","name":"untrusted-custom-feed"}]}}
                """).getAsJsonObject();
        var sources = new LinkedHashMap<String, ChatGPTSearchSources.Source>();
        ChatGPTSearchSources.collectItem(item, sources);
        assertEquals(2, sources.size());
        var weather = sources.get("api:oai-weather");
        assertEquals("oai-weather", weather.title()); assertEquals("", weather.url());
        Component links = ChatGPTSearchSources.links(Component.literal("Maid"), List.of(weather));
        assertNull(links.getSiblings().get(links.getSiblings().size() - 1).getStyle().getClickEvent());
    }

    @Test void citationsAreDeduplicatedAndOnlySafeWebUrlsBecomeClickable() {
        var sources = new LinkedHashMap<String, ChatGPTSearchSources.Source>();
        for (String url : List.of("https://example.com/source", "https://example.com/source", "javascript:alert(1)",
                "file:///C:/secret", "https://user:password@example.com/", "https:///broken")) {
            JsonObject annotation = new JsonObject(); annotation.addProperty("type", "url_citation");
            annotation.addProperty("url", url); annotation.addProperty("title", "A source");
            ChatGPTSearchSources.collectAnnotation(annotation, sources);
        }
        assertEquals(1, sources.size());
        Component links = ChatGPTSearchSources.links(Component.literal("Maid"), List.copyOf(sources.values()));
        var link = links.getSiblings().get(links.getSiblings().size() - 1);
        assertEquals(ClickEvent.Action.OPEN_URL, link.getStyle().getClickEvent().getAction());
        assertEquals("https://example.com/source", link.getStyle().getClickEvent().getValue());
    }

    @Test void onlyAnnotatedCitationLinksAreRemovedAndBothLanguageSectionsSurvive() {
        var body = JsonParser.parseString("""
                {"output":[{"type":"message","content":[{"type":"output_text",
                "text":"推荐版本。 ([Forge](https://forge.example/))\\n---\\nRecommended version.\\n[Download](https://downloads.example/)"}]}]}
                """).getAsJsonObject();
        ChatGPTSearchSources.cleanCitationText(body, List.of(new ChatGPTSearchSources.Source("Forge", "https://forge.example/")));
        String text = OpenAIResponsesCompatLLMClient.adaptResponse(body.toString()).getFirstChoice().getVisibleContent();
        assertFalse(text.contains("forge.example"));
        assertTrue(text.contains("---"));
        assertTrue(text.contains("Recommended version."));
        assertTrue(text.contains("[Download](https://downloads.example/)"));
    }
}
