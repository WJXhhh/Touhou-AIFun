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
            new FreeChatDecision.Thresholds(0.7D, 0.85D, 0.7D, 0.5D, 1.15D, 0.5D, 0.35D);

    private static FreeChatDecision.Candidate candidate(int index, int maidId, String name,
                                                        double distance, boolean busy) {
        return new FreeChatDecision.Candidate(index, maidId, name, distance, "front",
                true, true, busy, "刚才的回复");
    }

    private static FreeChatDecision.Candidate seenCandidate(int index, int maidId, String name,
                                                            boolean inView, boolean lookedAt,
                                                            String recentReply) {
        return new FreeChatDecision.Candidate(index, maidId, name, 3.0D, "front",
                inView, lookedAt, false, recentReply);
    }

    @Test
    void visibleMaidUsesTheLowerSightThreshold() {
        List<FreeChatDecision.Candidate> inView = List.of(
                seenCandidate(0, 10, "芙兰", true, false, "刚才的回复"));
        var inViewVerdict = FreeChatDecision.accept(
                answers("maid_0", 0.55D, "meaningful", 0.95D, "direct", 0.5D, "answer", 0.6D),
                inView, THRESHOLDS);
        assertTrue(inViewVerdict.accepted(), inViewVerdict.reason());

        List<FreeChatDecision.Candidate> lookedAt = List.of(
                seenCandidate(0, 10, "芙兰", false, true, "刚才的回复"));
        var lookedAtVerdict = FreeChatDecision.accept(
                answers("maid_0", 0.52D, "meaningful", 0.95D, "direct", 0.5D, "answer", 0.6D),
                lookedAt, THRESHOLDS);
        assertTrue(lookedAtVerdict.accepted(), lookedAtVerdict.reason());

        List<FreeChatDecision.Candidate> offScreen = List.of(
                seenCandidate(0, 10, "芙兰", false, false, "刚才的回复"));
        assertEquals(FreeChatDecision.REASON_LOW_CONFIDENCE, FreeChatDecision.accept(
                answers("maid_0", 0.55D, "meaningful", 0.95D, "direct", 0.5D, "answer", 0.6D),
                offScreen, THRESHOLDS).reason(),
                "Sight tiers must not lower the bar for an off-screen maid");
    }

    @Test
    void maidQuestionFollowUpUsesTheLowestThreshold() {
        List<FreeChatDecision.Candidate> candidate = List.of(
                seenCandidate(0, 10, "芙兰", true, false, "主人想吃蛋糕还是布丁呀？"));
        var accepted = FreeChatDecision.accept(
                answers("maid_0", 0.36D, "meaningful", 0.95D, "direct", 0.5D, "answer", 0.6D),
                candidate, THRESHOLDS);
        assertTrue(accepted.accepted(), accepted.reason());

        assertEquals(FreeChatDecision.REASON_LOW_CONFIDENCE, FreeChatDecision.accept(
                answers("maid_0", 0.30D, "meaningful", 0.95D, "direct", 0.5D, "answer", 0.6D),
                candidate, THRESHOLDS).reason());
    }

    @Test
    void sightThresholdOnlyAppliesToMaidTargets() {
        List<FreeChatDecision.Candidate> candidates = List.of(
                seenCandidate(0, 10, "芙兰", true, true, "刚才的回复"));
        // The other-player target is in view too, but its target confidence still uses the general
        // 0.7 gate; relatedness is not reached at 0.55.
        assertEquals(FreeChatDecision.REASON_LOW_CONFIDENCE, FreeChatDecision.accept(
                answers("other_player", 0.55D, "meaningful", 0.95D, "direct", 0.5D,
                        "answer", 0.6D), candidates, THRESHOLDS).reason());
    }

    @Test
    void sightThresholdNeverMakesTheGateStricterThanTheGeneralOne() {
        var stricterSight = new FreeChatDecision.Thresholds(
                0.7D, 0.85D, 0.7D, 0.5D, 1.15D, 0.9D, 0.9D);
        List<FreeChatDecision.Candidate> candidates = List.of(
                seenCandidate(0, 10, "芙兰", true, false, "刚才的回复"));
        var verdict = FreeChatDecision.accept(
                answers("maid_0", 0.75D, "meaningful", 0.95D, "direct", 0.5D, "answer", 0.6D),
                candidates, stricterSight);
        assertTrue(verdict.accepted(), verdict.reason());
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

    private static JsonObject choice(String value, double confidence, Map<String, Double> probabilities) {
        JsonObject answer = choice(value, confidence);
        JsonObject distribution = new JsonObject();
        probabilities.forEach(distribution::addProperty);
        answer.add("probabilities", distribution);
        return answer;
    }

    private static JsonObject answerSet(JsonObject target, JsonObject validity,
                                        JsonObject related, JsonObject intervention) {
        JsonObject answers = new JsonObject();
        answers.add("target", target);
        answers.add("validity", validity);
        answers.add("maid_related", related);
        answers.add("intervention", intervention);
        return answers;
    }

    @Test
    void softInterventionConfidenceDoesNotRejectAConfidentDirectAddress() {
        // Real Perplexity decider values for "芙兰你过来一下": the target is certain, the soft
        // four-way intervention question is not, but "ignore" is very unlikely.
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        JsonObject answers = answerSet(
                choice("maid_0", 1.0D),
                choice("meaningful", 0.97D),
                choice("direct", 0.51D),
                choice("answer", 0.35D, Map.of(
                        "answer", 0.51D, "brief_ack", 0.24D, "clarify", 0.14D, "ignore", 0.11D)));
        var verdict = FreeChatDecision.accept(answers, candidates, THRESHOLDS);
        assertTrue(verdict.accepted(), verdict.reason());
        assertEquals(FreeChatDecision.Action.ANSWER, verdict.action());
    }

    @Test
    void validityPositiveBranchIsNotGatedByTheTargetConfidence() {
        // Player-to-player chat the model considers meaningful but not obviously background-free
        // must reach the relatedness judgement instead of dying as low_confidence.
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        JsonObject answers = answerSet(
                choice("other_player", 0.96D),
                choice("meaningful", 0.77D, Map.of(
                        "meaningful", 0.85D, "too_short_incoherent", 0.0D, "background", 0.15D)),
                choice("direct", 0.81D, Map.of(
                        "direct", 0.85D, "relevant", 0.02D, "maid_general", 0.01D,
                        "unrelated", 0.09D, "unclear", 0.03D)),
                choice("brief_ack", 0.23D, Map.of(
                        "answer", 0.37D, "brief_ack", 0.42D, "clarify", 0.06D, "ignore", 0.14D)));
        var verdict = FreeChatDecision.accept(answers, candidates, THRESHOLDS);
        assertTrue(verdict.accepted(), verdict.reason());
        assertEquals(FreeChatDecision.Action.BRIEF_ACK, verdict.action());
        assertEquals(FreeChatDecision.Related.DIRECT, verdict.related());
    }

    @Test
    void playerRelatednessUsesProbabilityMassNotTheCalibratedConfidence() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        JsonObject relatedChat = answerSet(
                choice("other_player", 0.99D),
                choice("meaningful", 0.58D),
                choice("direct", 0.28D, Map.of(
                        "direct", 0.42D, "relevant", 0.42D, "maid_general", 0.03D,
                        "unrelated", 0.09D, "unclear", 0.04D)),
                choice("answer", 0.3D));
        // 0.84 mass is just below the 0.85 player bar.
        assertEquals(FreeChatDecision.REASON_NOT_RELATED,
                FreeChatDecision.accept(relatedChat, candidates, THRESHOLDS).reason());

        JsonObject clearer = answerSet(
                choice("other_player", 0.96D),
                choice("meaningful", 0.77D),
                choice("direct", 0.81D, Map.of(
                        "direct", 0.85D, "relevant", 0.02D, "maid_general", 0.01D,
                        "unrelated", 0.09D, "unclear", 0.03D)),
                choice("brief_ack", 0.23D, Map.of(
                        "answer", 0.37D, "brief_ack", 0.42D, "clarify", 0.06D, "ignore", 0.14D)));
        assertTrue(FreeChatDecision.accept(clearer, candidates, THRESHOLDS).accepted());
    }

    @Test
    void interventionFallsBackToConfidenceWhenNoDistributionIsGiven() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        JsonObject answers = answerSet(
                choice("maid_0", 0.99D),
                choice("meaningful", 0.99D),
                choice("direct", 0.6D),
                choice("answer", 0.3D));
        assertEquals(FreeChatDecision.REASON_LOW_CONFIDENCE,
                FreeChatDecision.accept(answers, candidates, THRESHOLDS).reason());
    }

    @Test
    void offGazePlayerConversationGetsARelatednessGain() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        // 0.80 mass: while the owner is clearly looking at the other player, that is below the
        // 0.85 player bar. Off-gaze the same evidence gets the x1.15 gain (-> 0.92) and reaches
        // the maid, because a player-to-player addressee is less certain when not looked at.
        JsonObject answers = answerSet(
                choice("other_player", 0.96D),
                choice("meaningful", 0.77D),
                choice("direct", 0.58D, Map.of(
                        "direct", 0.78D, "relevant", 0.02D, "maid_general", 0.01D,
                        "unrelated", 0.15D, "unclear", 0.04D)),
                choice("brief_ack", 0.23D, Map.of(
                        "answer", 0.37D, "brief_ack", 0.42D, "clarify", 0.06D, "ignore", 0.14D)));
        assertFalse(FreeChatDecision.accept(answers, candidates, THRESHOLDS, true).accepted());
        var offGaze = FreeChatDecision.accept(answers, candidates, THRESHOLDS, false);
        assertTrue(offGaze.accepted(), offGaze.reason());
        assertEquals(FreeChatDecision.Related.DIRECT, offGaze.related());
    }

    @Test
    void offGazeGainOnlyBoostsAndOnlyForPlayerAddressees() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        JsonObject playerAnswers = answerSet(
                choice("other_player", 0.96D),
                choice("meaningful", 0.77D),
                choice("direct", 0.58D, Map.of(
                        "direct", 0.78D, "relevant", 0.02D, "maid_general", 0.01D,
                        "unrelated", 0.15D, "unclear", 0.04D)),
                choice("brief_ack", 0.23D, Map.of(
                        "answer", 0.37D, "brief_ack", 0.42D, "clarify", 0.06D, "ignore", 0.14D)));

        // A gain below 1.0 must not make the maid more conservative; it is ignored.
        var noNerf = new FreeChatDecision.Thresholds(0.7D, 0.85D, 0.7D, 0.5D, 0.5D, 0.5D, 0.35D);
        assertFalse(FreeChatDecision.accept(playerAnswers, candidates, noNerf, false).accepted());

        // An unrelated player conversation stays unrelated even with the gain.
        JsonObject unrelated = answerSet(
                choice("other_player", 0.99D),
                choice("meaningful", 0.44D),
                choice("unrelated", 0.90D, Map.of(
                        "direct", 0.01D, "relevant", 0.00D, "maid_general", 0.02D,
                        "unrelated", 0.92D, "unclear", 0.05D)),
                choice("ignore", 0.94D));
        assertEquals(FreeChatDecision.REASON_NOT_RELATED,
                FreeChatDecision.accept(unrelated, candidates, THRESHOLDS, false).reason());

        // Other-entity relatedness uses its own bar and gets no player gaze gain.
        JsonObject otherEntity = answerSet(
                choice("other_entity", 0.97D),
                choice("meaningful", 0.88D),
                choice("relevant", 0.65D, Map.of(
                        "direct", 0.01D, "relevant", 0.65D, "maid_general", 0.03D,
                        "unrelated", 0.26D, "unclear", 0.05D)),
                choice("answer", 0.5D, Map.of(
                        "answer", 0.6D, "brief_ack", 0.2D, "clarify", 0.05D, "ignore", 0.15D)));
        assertEquals(FreeChatDecision.REASON_NOT_RELATED,
                FreeChatDecision.accept(otherEntity, candidates, THRESHOLDS, false).reason());
    }

    @Test
    void directCandidateAddressIgnoresTheGazeGain() {
        List<FreeChatDecision.Candidate> candidates = List.of(candidate(0, 10, "芙兰", 3.0D, false));
        JsonObject answers = answerSet(
                choice("maid_0", 0.99D),
                choice("meaningful", 0.97D),
                choice("unrelated", 0.1D),
                choice("answer", 0.35D, Map.of(
                        "answer", 0.51D, "brief_ack", 0.24D, "clarify", 0.14D, "ignore", 0.11D)));
        // Off-gaze direct address to the maid is still fine; the raised bar only applies to
        // player addressees.
        assertTrue(FreeChatDecision.accept(answers, candidates, THRESHOLDS, false).accepted());
    }

    @Test
    void summaryNeverContainsTranscriptText() {
        JsonObject answers = answerSet(
                choice("maid_0", 0.99D),
                choice("meaningful", 0.97D),
                choice("direct", 0.51D, Map.of(
                        "direct", 0.61D, "relevant", 0.03D, "maid_general", 0.02D,
                        "unrelated", 0.30D, "unclear", 0.04D)),
                choice("answer", 0.35D, Map.of(
                        "answer", 0.51D, "brief_ack", 0.24D, "clarify", 0.14D, "ignore", 0.11D)));
        String summary = FreeChatDecision.summary(answers);
        assertTrue(summary.contains("target=maid_0"));
        assertTrue(summary.contains("intervention=answer"));
        assertTrue(summary.contains("related_mass=0.64"));
        assertTrue(summary.contains("ignore_p=0.11"));
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
        List<FreeChatDecision.Candidate> offScreen = List.of(
                seenCandidate(0, 10, "灵梦", false, false, "刚才的回复"));
        assertEquals(FreeChatDecision.REASON_LOW_CONFIDENCE, FreeChatDecision.accept(
                answers("maid_0", 0.6D, "meaningful", 0.95D, "direct", 0.95D,
                        "answer", 0.95D), offScreen, THRESHOLDS).reason());
    }

    @Test
    void stateAndQuestionsExposeTheBoundedSceneAndCandidates() {
        List<FreeChatDecision.Candidate> candidates = List.of(
                candidate(0, 10, "灵梦", 3.0D, false),
                candidate(1, 11, "魔理沙", 5.0D, true));
        JsonObject state = FreeChatDecision.state("过来", 400,
                "Alice", "player <Steve> is visible", List.of("上一句", "再上一句"), candidates);
        assertEquals("free_chat_utterance", state.get("purpose").getAsString());
        assertEquals("speech_recognition", state.get("transcript_source").getAsString());
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
        assertTrue(questions.getAsJsonObject("target").get("instructions").getAsString()
                .contains("homophone"), "target question must tell the decider that ASR mishears names");
        assertTrue(questions.getAsJsonObject("maid_related").getAsJsonObject("criteria")
                        .get("direct").getAsString().contains("homophone"),
                "direct relatedness must accept a namesake near-miss");
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
