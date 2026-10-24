package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskStateStructureCompletionTest {
    private static JsonObject structure() {
        return JsonParser.parseString("""
                {"kind":"structure","dimension":"minecraft:overworld","structure_id":"abc-123"}
                """).getAsJsonObject();
    }

    @Test void acceptsDesignReferenceAndInlineBlocks() {
        assertEquals("abc-123", AgentTaskState.validateCompletion(structure()).get("structure_id").getAsString());
        JsonObject inline = JsonParser.parseString("""
                {"kind":"structure","dimension":"minecraft:overworld","strict":false,
                 "blocks":[{"x":1,"y":2,"z":3,"block":"minecraft:oak_planks","strict":true},
                           {"x":1,"y":2,"z":4,"expect":"air"}]}
                """).getAsJsonObject();
        assertEquals(2, AgentTaskState.validateCompletion(inline).getAsJsonArray("blocks").size());
    }

    @Test void rejectsMissingOrConflictingStructureTargets() {
        assertThrows(IllegalArgumentException.class, () -> AgentTaskState.validateCompletion(
                JsonParser.parseString("{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskState.validateCompletion(
                JsonParser.parseString("{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"structure_id\":\"a\",\"blocks\":[{\"x\":1,\"y\":2,\"z\":3,\"block\":\"minecraft:stone\"}]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskState.validateCompletion(
                JsonParser.parseString("{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"blocks\":[]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskState.validateCompletion(
                JsonParser.parseString("{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"blocks\":[{\"x\":1.5,\"y\":2,\"z\":3,\"block\":\"minecraft:stone\"}]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskState.validateCompletion(
                JsonParser.parseString("{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"strict\":\"yes\",\"blocks\":[{\"x\":1,\"y\":2,\"z\":3,\"block\":\"minecraft:stone\"}]}").getAsJsonObject()));
    }

    @Test void structureCanBeAnOrderedPhase() {
        JsonObject phases = JsonParser.parseString("""
                {"kind":"sequence","phases":[
                  {"kind":"structure","dimension":"minecraft:overworld","structure_id":"demo"},
                  {"kind":"inventory","dimension":"minecraft:overworld","item":"minecraft:stone","count":1}]}
                """).getAsJsonObject();
        assertEquals(2, AgentTaskState.validateCompletion(phases).getAsJsonArray("phases").size());
    }

    @Test void additiveMaterialAndQuestionFieldsAreRepairedAndBounded() {
        AgentTaskState task = new AgentTaskState("build");
        task.pendingGuidance = null;
        task.materialPolicy = "not-a-policy";
        task.pendingOwnerQuestion = "x".repeat(AgentTaskState.MAX_OWNER_QUESTION_CHARS + 1);
        task.ownerQuestionText = "q".repeat(AgentTaskState.MAX_OWNER_QUESTION_TEXT_CHARS + 1);
        task.awaitingOwnerInput = true;
        task.materialRounds = -4;
        task.validatePhaseCheckpoint();
        assertEquals(AgentTaskState.MATERIAL_ASK_OWNER, task.materialPolicy);
        assertEquals("", task.pendingOwnerQuestion);
        assertFalse(task.awaitingOwnerInput);
        assertEquals(AgentTaskState.MAX_OWNER_QUESTION_TEXT_CHARS, task.ownerQuestionText.length());
        assertEquals(0, task.materialRounds);
    }

    @Test void ownerQuestionCanBeRaisedAnsweredAndCleared() {
        AgentTaskState task = new AgentTaskState("build");
        task.raiseOwnerQuestion("{\"kind\":\"materials\"}", true);
        assertTrue(task.hasOwnerQuestion());
        task.markOwnerQuestionDelivered("要我自己去找吗？");
        assertEquals("要我自己去找吗？", task.ownerQuestionText);
        task.clearOwnerQuestion();
        assertFalse(task.hasOwnerQuestion());
        assertFalse(task.awaitingOwnerInput);
    }
}
