package com.wjx.touhou_aifun.chat.agent.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildStateDecisionTest {
    private static JsonObject answer(String choice, double confidence) {
        JsonObject probabilities = new JsonObject();
        probabilities.addProperty(choice, confidence);
        JsonObject answers = new JsonObject();
        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "choice");
        verdict.addProperty("choice", choice);
        verdict.addProperty("confidence", confidence);
        verdict.add("probabilities", probabilities);
        answers.add("verdict", verdict);
        return answers;
    }

    @Test void verdictJsonUsesConfidentChoiceAndAdjustment() {
        JsonObject answers = answer("adjust", 0.9);
        JsonObject candidate = new JsonObject();
        candidate.addProperty("type", "choice");
        candidate.addProperty("choice", "c0");
        candidate.addProperty("confidence", 0.9);
        JsonObject probabilities = new JsonObject();
        probabilities.addProperty("c0", 0.9);
        candidate.add("probabilities", probabilities);
        answers.add("candidate", candidate);

        JsonObject adjustment = new JsonObject();
        adjustment.addProperty("face", "north");
        List<BuildStateDecision.Candidate> candidates = List.of(
                new BuildStateDecision.Candidate("c0", "north face", adjustment));
        BuildStateDecision.Verdict verdict = BuildStateDecision.parse(answers, 0.7, candidates, null);
        assertTrue(verdict.confident());
        assertEquals("adjust", verdict.choice());
        assertEquals("north", verdict.adjustment().get("face").getAsString());
        assertTrue(verdict.json().get("confident").getAsBoolean());
    }

    @Test void requiredStepCannotBeSkipped() {
        BuildStep step = new BuildStep(1, "place", 0, 0, 0);
        step.required = true;
        BuildStateDecision.Verdict verdict = BuildStateDecision.parse(answer("skip", 0.95), 0.7, List.of(), step);
        assertFalse(verdict.confident());
        assertEquals("llm", verdict.choice());
    }

    @Test void lowConfidenceAndMissingCandidateFallBackToLlm() {
        assertFalse(BuildStateDecision.parse(answer("satisfied", 0.6), 0.7, List.of(), null).confident());
        JsonObject adjust = answer("adjust", 0.9);
        assertFalse(BuildStateDecision.parse(adjust, 0.7, List.of(), null).confident());
    }

    @Test void questionsOfferVerdictAndCandidateOnlyWhenAvailable() {
        JsonObject none = BuildStateDecision.questions(List.of());
        assertTrue(none.has("verdict"));
        assertFalse(none.has("candidate"));
        JsonObject one = BuildStateDecision.questions(List.of(new BuildStateDecision.Candidate("c0", "support", new JsonObject())));
        assertTrue(one.has("candidate"));
        assertTrue(one.getAsJsonObject("candidate").getAsJsonObject("criteria").has("c0"));
    }

    @Test void stateNeverContainsContainerOrNbtContent() {
        BuildDesign design = new BuildDesign();
        design.structureName = "wall";
        BuildStep step = new BuildStep(3, "place", 5, 64, 7);
        step.block = "minecraft:stone";
        step.item = "minecraft:stone";
        JsonObject actual = new JsonObject();
        actual.addProperty("block", "minecraft:stone");
        actual.addProperty("block_entity", "minecraft:chest");
        JsonObject state = BuildStateDecision.state(null, design, step, actual, 2, List.of());
        assertEquals("build_state", state.get("purpose").getAsString());
        assertEquals("wal", state.get("structure").getAsString().substring(0, 3));
        assertFalse(state.toString().contains("container_inventory"));
        assertFalse(state.toString().contains("\"items\""));
        assertEquals(3, state.getAsJsonObject("step").get("index").getAsInt());
    }

    @Test void needsLlmIsNeverConfident() {
        JsonObject json = BuildStateDecision.lowConfidence("decision_timeout");
        assertFalse(json.get("confident").getAsBoolean());
        assertEquals("llm", json.get("choice").getAsString());
        assertTrue(json.get("reason").getAsString().contains("decision_timeout"));
    }
}
