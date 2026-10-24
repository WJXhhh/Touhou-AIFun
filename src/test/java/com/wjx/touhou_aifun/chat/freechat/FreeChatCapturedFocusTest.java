package com.wjx.touhou_aifun.chat.freechat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeChatCapturedFocusTest {
    private static FreeChatDecision.Candidate receipt(int index, int maidId, boolean inView) {
        return new FreeChatDecision.Candidate(index, maidId, "maid", 3.0D, "front", inView,
                inView, false, "Which material do you mean?");
    }

    @Test
    void onsetFocusSurvivesTurningAwayDuringAsrAndUsesQuestionThreshold() {
        var scene = FreeChatCapturedFocus.decode(FreeChatCapturedFocus.encode("onset scene",
                List.of(new FreeChatCapturedFocus.Focus(42, true, true, "left"))));
        var candidate = scene.apply(receipt(0, 42, false));
        assertEquals("onset scene", scene.summary());
        assertTrue(candidate.inView());
        assertTrue(candidate.beingLookedAt());
        assertEquals("left", candidate.direction());
        var answers = new JsonObject();
        answers.add("target", choice("maid_0", 0.36D));
        answers.add("validity", choice("meaningful", 0.86D));
        answers.add("intervention", choice("answer", 0.8D));
        var thresholds = new FreeChatDecision.Thresholds(0.7D, 0.85D, 0.7D, 0.5D,
                1.15D, 0.5D, 0.35D);
        assertFalse(FreeChatDecision.accept(answers, List.of(receipt(0, 42, false)), thresholds).accepted());
        assertTrue(FreeChatDecision.accept(answers, List.of(candidate), thresholds).accepted());
    }

    @Test
    void captureIsBoundToEntityIdAndCannotCreateCandidates() {
        var scene = FreeChatCapturedFocus.decode(FreeChatCapturedFocus.encode("scene",
                List.of(new FreeChatCapturedFocus.Focus(42, true, true, "front"),
                        new FreeChatCapturedFocus.Focus(43, false, false, "back"))));
        assertTrue(scene.apply(receipt(1, 42, false)).inView());
        assertFalse(scene.apply(receipt(0, 43, true)).inView());
        var other = receipt(0, 99, false);
        assertSame(other, scene.apply(other));
    }

    @Test
    void legacyAndMalformedMetadataKeepReceiptFocus() {
        var candidate = receipt(0, 42, false);
        for (String scene : List.of("legacy scene player_looking_at_her=true",
                "@aifun_focus_v1 [[42,true,true,\"front\"]]", "@aifun_focus_v1 broken\nscene",
                "@aifun_focus_v1 [[42,\"true\",true,\"front\"]]\nscene",
                "@aifun_focus_v1 [[42.5,true,true,\"front\"]]\nscene",
                "@aifun_focus_v1 [[42,true,true,\"front\"],[42,false,false,\"back\"]]\nscene",
                "@aifun_focus_v1 [[42,true,true,\"instructions\"]]\nscene")) {
            var decoded = FreeChatCapturedFocus.decode(scene);
            assertTrue(decoded.focus().isEmpty(), scene);
            assertSame(candidate, decoded.apply(candidate));
        }
    }

    @Test
    void longSceneKeepsAllFocusRowsAndStaysInsidePacketBudget() {
        var focus = java.util.stream.IntStream.range(0, 12)
                .mapToObj(id -> new FreeChatCapturedFocus.Focus(id, true, false, "front")).toList();
        String encoded = FreeChatCapturedFocus.encode("\ud83d\ude00".repeat(2100), focus);
        assertTrue(encoded.codePointCount(0, encoded.length()) <= 2000);
        var decoded = FreeChatCapturedFocus.decode(encoded);
        assertEquals(8, decoded.focus().size());
        assertTrue(decoded.summary().endsWith("\ud83d\ude00"));
    }

    private static JsonObject choice(String value, double confidence) {
        var answer = new JsonObject();
        answer.addProperty("type", "choice");
        answer.addProperty("choice", value);
        answer.addProperty("confidence", confidence);
        return answer;
    }
}
