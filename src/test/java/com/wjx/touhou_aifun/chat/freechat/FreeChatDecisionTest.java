package com.wjx.touhou_aifun.chat.freechat;

import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionProtocol;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeChatDecisionTest {
    private static final FreeChatDecision.Thresholds THRESHOLDS =
            new FreeChatDecision.Thresholds(0.7D, 0.85D, 0.7D);

    private static FreeChatDecision.Candidate candidate(int index, int maidId, String name,
                                                        double distance, boolean busy) {
        return new FreeChatDecision.Candidate(index, maidId, name, distance, "front",
                true, true, busy, "刚才的回复");
    }

    private static JsonObject answers(String target, double targetConfidence,
                                      String validity, double validityConfidence,
                                      String related, double relatedConfidence,
                                      String intervention, double interventionConfidence) {
        JsonObject answers = new JsonObject();
        answers.add("target", choice(target, targetConfidence));
        answers.add("validity", choice(validity, validityConfidence));
        answers.add("maid_related", choice(related, relatedConfidence));
        answers.add("intervention", choice(intervention, interventionConfidence));
        return answers;
    }

    private static JsonObject choice(String value, double confidence) {
        JsonObject answer = new JsonObject();
        answer.addProperty("type", "choice");
        answer.addProperty("choice", value);
        answer.addProperty("confidence", confidence);
        return answer;
    }

    @Test
    void directCandidateAddressIsAcceptedWithoutExtraRelatedness() {
        List<FreeChatDecision.Candidate> candidates = List.of(
                candidate(0, 10, "灵梦", 3.0D, false),
                candidate(1, 11, "魔理沙", 5.0D, true));
        var verdict = FreeChatDecision.accept(
                answers("maid_0", 0.95D, "meaningful", 0.95D, "unrelated", 0.1D,
                        "answer", 0.9D),
                candidates, THRESHOLDS);
        assertTrue(verdict.accepted());
        assertEquals(10, verdict.responder().maidId());
        assertEquals(FreeChatDecision.Action.ANSWER, verdict.action());
    }

    @Test
    void playerConversationNeedsTheHigherRelatedBar() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "灵梦", 3.0D, false));
        var accepted = FreeChatDecision.accept(
                answers("other_player", 0.95D, "meaningful", 0.95D, "direct", 0.9D,
                        "answer", 0.9D),
                candidates, THRESHOLDS);
        assertTrue(accepted.accepted());
        assertEquals(10, accepted.responder().maidId());

        var rejected = FreeChatDecision.accept(
                answers("other_player", 0.95D, "meaningful", 0.95D, "direct", 0.8D,
                        "answer", 0.9D),
                candidates, THRESHOLDS);
        assertFalse(rejected.accepted());
        assertEquals(FreeChatDecision.REASON_NOT_RELATED, rejected.reason());
    }

    @Test
    void otherEntitiesUseTheLowerRelatedBar() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "灵梦", 3.0D, false));
        var accepted = FreeChatDecision.accept(
                answers("other_entity", 0.95D, "meaningful", 0.95D, "relevant", 0.75D,
                        "clarify", 0.9D),
                candidates, THRESHOLDS);
        assertTrue(accepted.accepted());
        assertEquals(FreeChatDecision.Action.CLARIFY, accepted.action());

        var environment = FreeChatDecision.accept(
                answers("environment", 0.95D, "meaningful", 0.95D, "relevant", 0.72D,
                        "brief_ack", 0.9D),
                candidates, THRESHOLDS);
        assertTrue(environment.accepted());

        var rejected = FreeChatDecision.accept(
                answers("environment", 0.95D, "meaningful", 0.95D, "relevant", 0.65D,
                        "answer", 0.9D),
                candidates, THRESHOLDS);
        assertFalse(rejected.accepted());
        assertEquals(FreeChatDecision.REASON_NOT_RELATED, rejected.reason());
    }

    @Test
    void nonCandidateTargetUsesTheFirstNonBusyCandidate() {
        List<FreeChatDecision.Candidate> candidates = List.of(
                candidate(0, 10, "灵梦", 3.0D, true),
                candidate(1, 11, "魔理沙", 5.0D, false));
        var verdict = FreeChatDecision.accept(
                answers("other_player", 0.95D, "meaningful", 0.95D, "relevant", 0.9D,
                        "answer", 0.9D),
                candidates, THRESHOLDS);
        assertTrue(verdict.accepted());
        assertEquals(11, verdict.responder().maidId());
    }

    @Test
    void validityAndInterventionRulesRejectInTheRightOrder() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "灵梦", 3.0D, false));

        assertEquals(FreeChatDecision.REASON_INVALID_SPEECH, FreeChatDecision.accept(
                answers("maid_0", 0.95D, "too_short_incoherent", 0.95D, "direct", 0.95D,
                        "answer", 0.95D), candidates, THRESHOLDS).reason());
        assertEquals(FreeChatDecision.REASON_BACKGROUND_SPEECH, FreeChatDecision.accept(
                answers("maid_0", 0.95D, "background", 0.95D, "direct", 0.95D,
                        "answer", 0.95D), candidates, THRESHOLDS).reason());
        assertEquals(FreeChatDecision.REASON_IGNORED, FreeChatDecision.accept(
                answers("maid_0", 0.95D, "meaningful", 0.95D, "direct", 0.95D,
                        "ignore", 0.95D), candidates, THRESHOLDS).reason());
        assertEquals(FreeChatDecision.REASON_NOT_ADDRESSED, FreeChatDecision.accept(
                answers("ambient", 0.95D, "meaningful", 0.95D, "direct", 0.95D,
                        "answer", 0.95D), candidates, THRESHOLDS).reason());
        assertEquals(FreeChatDecision.REASON_LOW_CONFIDENCE, FreeChatDecision.accept(
                answers("maid_0", 0.6D, "meaningful", 0.95D, "direct", 0.95D,
                        "answer", 0.95D), candidates, THRESHOLDS).reason());
    }

    @Test
    void stateAndQuestionsExposeTheBoundedSceneAndCandidates() {
        List<FreeChatDecision.Candidate> candidates = List.of(
                candidate(0, 10, "灵梦", 3.0D, false),
                candidate(1, 11, "魔理沙", 5.0D, true));
        JsonObject state = FreeChatDecision.state("过来", 400,
                "Alice", "player <Steve> is visible", List.of("上一句", "再上一句"), candidates);
        assertEquals("free_chat_utterance", state.get("purpose").getAsString());
        assertEquals(2, state.getAsJsonArray("candidates").size());
        assertTrue(state.get("player_view").getAsString().contains("[Steve]"));
        assertEquals(2, state.getAsJsonArray("recent_ambient_speech").size());
        assertTrue(state.getAsJsonObject("text_features").get("very_short").getAsBoolean());

        JsonObject questions = FreeChatDecision.questions(candidates);
        JsonObject targetCriteria = questions.getAsJsonObject("target")
                .getAsJsonObject("criteria");
        for (String key : List.of("maid_0", "maid_1", "other_player", "other_entity",
                "environment", "ambient", "unknown")) {
            assertTrue(targetCriteria.has(key), key);
        }
        assertNotNull(questions.getAsJsonObject("validity"));
        assertNotNull(questions.getAsJsonObject("maid_related"));
        assertNotNull(questions.getAsJsonObject("intervention"));
    }

    @Test
    void questionsAndAnswersPassTheSharedDecisionProtocol() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "灵梦", 3.0D, false));
        JsonObject state = FreeChatDecision.state("过来", 400, "Alice", "scene",
                List.of("上一句"), candidates);
        JsonObject questions = FreeChatDecision.questions(candidates);
        var capabilities = new DecisionProvider.Capabilities(128, 255, 10, false);
        String body = DecisionProtocol.request("mock-model", state, questions, capabilities).toString();
        assertTrue(body.contains("free_chat_utterance"));

        JsonObject answers = new JsonObject();
        answers.add("target", protocolChoice("maid_0",
                List.of("maid_0", "other_player", "other_entity", "environment", "ambient", "unknown")));
        answers.add("validity", protocolChoice("meaningful",
                List.of("meaningful", "too_short_incoherent", "background")));
        answers.add("maid_related", protocolChoice("direct",
                List.of("direct", "relevant", "maid_general", "unrelated", "unclear")));
        answers.add("intervention", protocolChoice("answer",
                List.of("answer", "brief_ack", "clarify", "ignore")));
        JsonObject response = new JsonObject();
        response.addProperty("model", "mock-model");
        response.add("answers", answers);
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", 100);
        usage.addProperty("output_tokens", 4);
        response.add("usage", usage);
        var result = DecisionProtocol.response(response.toString(), questions, "request-1");
        var verdict = FreeChatDecision.accept(result.answers(), candidates, THRESHOLDS);
        assertTrue(verdict.accepted());
        assertEquals(10, verdict.responder().maidId());
    }

    private static JsonObject protocolChoice(String chosen, List<String> alternatives) {
        JsonObject answer = new JsonObject();
        answer.addProperty("type", "choice");
        answer.addProperty("choice", chosen);
        answer.addProperty("confidence", 0.95D);
        JsonObject probabilities = new JsonObject();
        Map<String, Double> values = new LinkedHashMap<>();
        double weight = 0.05D / Math.max(1, alternatives.size() - 1);
        for (String alternative : alternatives) {
            values.put(alternative, alternative.equals(chosen) ? 0.95D : weight);
        }
        values.forEach(probabilities::addProperty);
        answer.add("probabilities", probabilities);
        return answer;
    }

    @Test
    void ambientFactsExplainTheAddresseeAndAction() {
        FreeChatDecision.Candidate responder = candidate(0, 10, "灵梦", 3.0D, false);
        String facts = FreeChatDecision.ambientFacts(FreeChatDecision.TargetKind.OTHER_PLAYER,
                FreeChatDecision.Action.CLARIFY, FreeChatDecision.Related.RELEVANT,
                "scene <data>", List.of(responder), responder, List.of("上一句"));
        assertTrue(facts.contains("another player"));
        assertTrue(facts.contains("ask one brief clarification"));
        assertTrue(facts.contains("Recent ambient speech"));
        assertTrue(facts.contains("Do not mention"));
        assertFalse(facts.contains("<data>"));
    }
}
