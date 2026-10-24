package com.wjx.touhou_aifun.chat.freechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured onset focus inside the existing scene string; old clients retain receipt-time focus. */
public final class FreeChatCapturedFocus {
    private static final String PREFIX = "@aifun_focus_v1 ";
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_SCENE = 2000;

    public record Focus(int maidId, boolean inView, boolean beingLookedAt, String direction) {
    }

    public record Scene(String summary, Map<Integer, Focus> focus) {
        /** Apply only after the server has validated this candidate's ownership and range. */
        public FreeChatDecision.Candidate apply(FreeChatDecision.Candidate candidate) {
            Focus captured = focus.get(candidate.maidId());
            if (captured == null) {
                return candidate;
            }
            return new FreeChatDecision.Candidate(candidate.index(), candidate.maidId(), candidate.name(),
                    candidate.distance(), captured.direction(), captured.inView(), captured.beingLookedAt(),
                    candidate.busy(), candidate.recentReply());
        }
    }

    private FreeChatCapturedFocus() {
    }

    public static String encode(String summary, List<Focus> focus) {
        JsonArray rows = new JsonArray();
        for (Focus captured : focus.stream().limit(MAX_CANDIDATES).toList()) {
            JsonArray row = new JsonArray();
            row.add(captured.maidId());
            row.add(captured.inView());
            row.add(captured.beingLookedAt());
            row.add(captured.direction());
            rows.add(row);
        }
        String header = PREFIX + rows + "\n";
        return header + FreeChatDecision.sanitizePrompt(summary, MAX_SCENE - header.length());
    }

    public static Scene decode(String value) {
        String scene = FreeChatDecision.sanitizePrompt(value, MAX_SCENE);
        if (!scene.startsWith(PREFIX)) {
            return new Scene(scene, Map.of());
        }
        int newline = scene.indexOf('\n');
        if (newline < 0) {
            return new Scene(scene, Map.of());
        }
        try {
            JsonArray rows = JsonParser.parseString(scene.substring(PREFIX.length(), newline)).getAsJsonArray();
            if (rows.size() > MAX_CANDIDATES) {
                return new Scene(scene, Map.of());
            }
            Map<Integer, Focus> focus = new LinkedHashMap<>();
            for (JsonElement element : rows) {
                JsonArray row = element.getAsJsonArray();
                if (row.size() != 4 || !row.get(0).isJsonPrimitive()
                        || !row.get(0).getAsJsonPrimitive().isNumber()
                        || !row.get(1).isJsonPrimitive() || !row.get(1).getAsJsonPrimitive().isBoolean()
                        || !row.get(2).isJsonPrimitive() || !row.get(2).getAsJsonPrimitive().isBoolean()
                        || !row.get(3).isJsonPrimitive() || !row.get(3).getAsJsonPrimitive().isString()
                        || !List.of("front", "back", "left", "right", "up", "down")
                        .contains(row.get(3).getAsString())) {
                    return new Scene(scene, Map.of());
                }
                int maidId = row.get(0).getAsBigDecimal().intValueExact();
                if (focus.putIfAbsent(maidId,
                        new Focus(maidId, row.get(1).getAsBoolean(), row.get(2).getAsBoolean(),
                                row.get(3).getAsString())) != null) {
                    return new Scene(scene, Map.of());
                }
            }
            return new Scene(scene.substring(newline + 1), Map.copyOf(focus));
        } catch (RuntimeException malformed) {
            return new Scene(scene, Map.of());
        }
    }
}
