package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskRecoveryPolicyTest {
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    @Test void staleObservationsRefreshWithoutASecondDecisionAndOwnerChangesStop(){assertEquals(TaskRecoveryPolicy.Kind.REINSPECT,TaskRecoveryPolicy.classify(TaskRecoveryPolicy.reason("{\"status\":\"failed\",\"error\":\"stale_slots_reinspect\"}")));assertEquals(TaskRecoveryPolicy.Kind.STOP,TaskRecoveryPolicy.classify("owner_changed"));assertEquals(TaskRecoveryPolicy.Kind.DELEGATE,TaskRecoveryPolicy.classify("unknown_server_error"));assertEquals(TaskRecoveryPolicy.Kind.CHOOSE,TaskRecoveryPolicy.classify("movement_blocked"));}
    @Test void mutationWithoutVerifiedOutcomeCannotBeReplayed(){var action=new ActionPlan.Action("gui_action",json("{\"action\":\"transfer\"}"),"Transfer");assertFalse(TaskRecoveryPolicy.repeatable(new ActionPlan.Step("Transfer",List.of(action),null,null)));assertTrue(TaskRecoveryPolicy.repeatable(new ActionPlan.Step("Transfer",List.of(action),null,json("{\"kind\":\"transfer\"}"))));assertFalse(TaskRecoveryPolicy.repeatable(new ActionPlan.Step("Trade",List.of(new ActionPlan.Action("gui_action",json("{\"action\":\"trade\"}"),"Trade")),null,json("{\"kind\":\"transfer\"}"))));}
    @Test void mixedSafeAndUnsafeAlternativesCannotAuthorizeAReplay(){assertFalse(TaskRecoveryPolicy.repeatable(new ActionPlan.Step("Choose",List.of(new ActionPlan.Action("inspect_gui",new JsonObject(),"Inspect"),new ActionPlan.Action("eat_food_block",new JsonObject(),"Eat")),null,null)));}
}
