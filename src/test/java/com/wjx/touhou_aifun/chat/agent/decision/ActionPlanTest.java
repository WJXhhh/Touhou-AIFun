package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionPlanTest {
    @Test void legacyTasksStayReadableAndOwnerAmendmentsInvalidateExecutableTemplates() {
        var task=new Gson().fromJson("{\"goal\":\"old goal\",\"steps\":[\"old step\"]}",AgentTaskState.class);
        assertTrue(task.actionSteps.isEmpty());assertTrue(task.actionFacts.isEmpty());
        task.actionSteps=JsonParser.parseString("[{\"description\":\"Follow\",\"alternatives\":[{\"tool\":\"switch_follow_state\",\"arguments_json\":\"{}\"}]}]").getAsJsonArray();
        assertEquals(1,ActionPlan.validate(task.actionSteps).size());task.actionPlanGoalVersion=1;
        task.amend("Sit instead");task.applyAmendment();assertEquals(2,task.goalVersion);assertTrue(task.actionSteps.isEmpty());
    }
    @Test void impossiblePlansAndQueueControlCannotBecomeDecisionActions() {
        assertThrows(IllegalArgumentException.class,()->ActionPlan.validate(new JsonArray()));
        var row=JsonParser.parseString("[{\"description\":\"Queue\",\"alternatives\":[{\"tool\":\"task_control\"}]}]").getAsJsonArray();
        assertThrows(IllegalArgumentException.class,()->ActionPlan.validate(row));
    }
    @Test void stateAndConsumptionContractsRequireVerifiableTypedFacts() {
        var condition=JsonParser.parseString("{\"kind\":\"maid_state\",\"dimension\":\"minecraft:overworld\",\"state\":\"follow\",\"value\":\"true\"}").getAsJsonObject();
        assertEquals(condition,AgentTaskState.validateCompletion(condition));condition.addProperty("value","maybe");assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(condition));
        var food=JsonParser.parseString("{\"kind\":\"eat_food\",\"dimension\":\"minecraft:overworld\",\"food\":\"minecraft:cake\",\"count\":1,\"finished\":true}").getAsJsonObject();
        assertEquals(food,AgentTaskState.validateCompletion(food));food.addProperty("count",1.5);assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(food));
    }
}
