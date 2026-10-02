package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.wjx.touhou_aifun.compat.ai.chatgpt.OpenAIIdentity.string;

/** Native citation annotations stay outside spoken text and become clickable game-chat links. */
final class ChatGPTSearchSources {
    record Source(String title, String url, boolean cited) {
        Source(String title, String url) { this(title, url, true); }
    }
    private ChatGPTSearchSources() { }

    static void collectItem(JsonObject item, Map<String, Source> sources) {
        if ("web_search_call".equals(string(item, "type")) && item.has("action") && item.get("action").isJsonObject()) {
            JsonObject action = item.getAsJsonObject("action");
            if (action.has("sources") && action.get("sources").isJsonArray()) {
                for (var source : action.getAsJsonArray("sources"))
                    if (source.isJsonObject()) collectSearchSource(source.getAsJsonObject(), sources);
            }
            return;
        }
        if (!"message".equals(string(item, "type")) || !item.has("content") || !item.get("content").isJsonArray()) return;
        for (var element : item.getAsJsonArray("content")) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (!block.has("annotations") || !block.get("annotations").isJsonArray()) continue;
            for (var annotation : block.getAsJsonArray("annotations"))
                if (annotation.isJsonObject()) collectAnnotation(annotation.getAsJsonObject(), sources);
        }
    }

    static void collectAnnotation(JsonObject annotation, Map<String, Source> sources) {
        if (!"url_citation".equals(string(annotation, "type"))) return;
        collectWebSource(annotation, sources, true);
    }

    private static void collectSearchSource(JsonObject source, Map<String, Source> sources) {
        if (sources.size() >= 64) return;
        if ("api".equals(string(source, "type"))) {
            String name = string(source, "name");
            if (List.of("oai-weather", "oai-finance", "oai-sports").contains(name))
                sources.putIfAbsent("api:" + name, new Source(name, "", false));
        } else collectWebSource(source, sources, false);
    }

    private static void collectWebSource(JsonObject annotation, Map<String, Source> sources, boolean cited) {
        String url = string(annotation, "url");
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return;
            String title = string(annotation, "title").replaceAll("[\\p{Cntrl}]", " ").strip();
            if (title.isBlank()) title = uri.getHost();
            title = title.substring(0, Math.min(title.length(), 160));
            Source old = sources.get(url);
            if (old == null && sources.size() >= 64) {
                if (!cited) return;
                String evict = sources.entrySet().stream().filter(entry -> !entry.getValue().cited()
                        && !entry.getValue().url().isBlank()).map(Map.Entry::getKey).findFirst().orElse(null);
                if (evict == null) return;
                sources.remove(evict);
            }
            if (old != null && string(annotation, "title").isBlank()) title = old.title();
            sources.put(url, new Source(title, url, cited || (old != null && old.cited())));
        } catch (IllegalArgumentException ignored) { }
    }

    static List<Source> displaySources(Map<String, Source> sources) {
        var relevant = sources.values().stream().filter(source -> source.cited() || source.url().isBlank()).toList();
        // Browsed pages are not all evidence for the answer. Prefer actual citations and data feeds;
        // show a small fallback only when the response has no cited sources at all.
        return relevant.isEmpty() ? sources.values().stream().limit(3).toList() : relevant;
    }

    static MutableComponent links(Component maidName, List<Source> sources) {
        MutableComponent result = Component.literal("<").append(maidName).append("> ")
                .append(Component.translatable("gui.touhou_aifun.chatgpt.search_sources"));
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            if (source.url().isBlank()) {
                result.append(" ").append(Component.translatable("gui.touhou_aifun.chatgpt.feed." + source.title())
                        .withStyle(ChatFormatting.GRAY));
                continue;
            }
            String label = source.title().length() > 24 ? source.title().substring(0, 24) + "…" : source.title();
            result.append(" ").append(Component.literal("[" + (i + 1) + "] " + label).withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, source.url()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(source.title() + "\n" + source.url())))));
        }
        return result;
    }

    static void cleanCitationText(JsonObject response, List<Source> sources) {
        if (sources.isEmpty()) return;
        if (response.has("output_text")) response.addProperty("output_text", cleanText(string(response, "output_text"), sources));
        if (!response.has("output") || !response.get("output").isJsonArray()) return;
        for (var element : response.getAsJsonArray("output")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!"message".equals(string(item, "type")) || !item.has("content")) continue;
            for (var part : item.getAsJsonArray("content")) {
                if (!part.isJsonObject()) continue;
                JsonObject block = part.getAsJsonObject();
                if ("output_text".equals(string(block, "type"))) block.addProperty("text", cleanText(string(block, "text"), sources));
            }
        }
    }

    private static String cleanText(String text, List<Source> sources) {
        // Citations are shown in a separate clickable chat message; omit their URL markup from
        // the spoken/display reply. Only remove links backed by actual citation annotations.
        for (Source source : sources) {
            if (source.url().isBlank() || !source.cited()) continue;
            String link = "\\[[^\\]\\r\\n]*\\]\\(<?" + Pattern.quote(source.url()) + ">?\\)";
            text = text.replaceAll("\\(" + link + "\\)", "").replaceAll(link, "");
        }
        return text.replaceAll("[ \\t]+([。，,.!?！？])", "$1").strip();
    }
}
