package com.wjx.touhou_aifun.chat.agent.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaterialAuthorizationDecisionTest {
    @Test void questionCoversAllFourPoliciesAndNeverTreatsBuildAsPermission() {
        JsonObject questions = MaterialAuthorizationDecision.questions();
        JsonObject criteria = questions.getAsJsonObject("authorization").getAsJsonObject("criteria");
        for (String policy : new String[]{AgentTaskState.MATERIAL_SELF_GATHER, AgentTaskState.MATERIAL_SELF_SOURCE,
                AgentTaskState.MATERIAL_DENIED, AgentTaskState.MATERIAL_ASK_OWNER}) {
            assertTrue(criteria.has(policy), policy);
        }
        assertTrue(questions.getAsJsonObject("authorization").get("instructions").getAsString().contains("NOT authorization"));
    }

    @Test void highConfidenceExplicitPermissionIsAccepted() {
        JsonObject answers = answer(AgentTaskState.MATERIAL_SELF_SOURCE, 0.93);
        assertEquals(AgentTaskState.MATERIAL_SELF_SOURCE,
                MaterialAuthorizationDecision.parse(answers, 0.8));
    }

    @Test void lowConfidenceOrInvalidAnswerAlwaysAsks() {
        assertFalse(MaterialAuthorizationDecision.parse(answer(AgentTaskState.MATERIAL_SELF_GATHER, 0.6), 0.8)
                .equals(AgentTaskState.MATERIAL_SELF_GATHER));
        assertEquals(AgentTaskState.MATERIAL_ASK_OWNER,
                MaterialAuthorizationDecision.parse(new JsonObject(), 0.8));
        assertEquals(AgentTaskState.MATERIAL_ASK_OWNER,
                MaterialAuthorizationDecision.parse(JsonParser.parseString("{\"authorization\":{}}").getAsJsonObject(), 0.8));
    }

    @Test void stateContainsGoalConstraintsMissingItemsButNoChat() {
        AgentTaskState task = new AgentTaskState("帮我建个房子");
        task.constraints.add("不要动箱子里的东西");
        task.pendingGuidance = "缺材料可以自己想办法";
        JsonArray missing = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("item", "minecraft:oak_planks");
        item.addProperty("required", 8);
        missing.add(item);
        JsonObject state = MaterialAuthorizationDecision.state(task, missing);
        assertEquals("material_authorization", state.get("purpose").getAsString());
        assertEquals(1, state.getAsJsonArray("constraints").size());
        assertEquals(1, state.getAsJsonArray("missing_items").size());
        assertFalse(state.toString().contains("conversation"));
        assertFalse(state.toString().contains("history"));
    }

    private static JsonObject answer(String choice, double confidence) {
        JsonObject probabilities = new JsonObject();
        probabilities.addProperty(choice, confidence);
        JsonObject answer = new JsonObject();
        answer.addProperty("type", "choice");
        answer.addProperty("choice", choice);
        answer.addProperty("confidence", confidence);
        answer.add("probabilities", probabilities);
        JsonObject answers = new JsonObject();
        answers.add("authorization", answer);
        return answers;
    }
}
