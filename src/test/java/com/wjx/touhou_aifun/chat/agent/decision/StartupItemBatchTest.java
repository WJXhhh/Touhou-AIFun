package com.wjx.touhou_aifun.chat.agent.decision;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StartupItemBatchTest {
    private static JsonObject templates(){return DecisionProtocol.choice("Full template",Map.of("receive","Receive","deliver_then_follow","Deliver then follow","follow","Follow","llm","Plan"));}
    @Test void bothBranchesShareStartupWithoutOverwritingFullGoalCoverage(){
        var batch=StartupItemBatch.prepare("每箱5个金锭",Map.of("minecraft:gold_ingot",64),Map.of("minecraft:iron_ingot",64),templates());
        var base=new JsonObject();base.add("template",templates());base.add("coverage",DecisionProtocol.noul("ENTIRE goal"));var merged=batch.append(base);
        assertEquals("ENTIRE goal",merged.getAsJsonObject("coverage").get("instructions").getAsString());assertTrue(merged.has("receive_item_0"));assertTrue(merged.has("deliver_item_0"));
        var answers=new JsonObject();answers.add("receive_item_0",JsonParser.parseString("{\"choice\":\"n_5\",\"confidence\":0.99}"));answers.add("deliver_item_0",JsonParser.parseString("{\"choice\":\"skip\",\"confidence\":0.1}"));
        assertEquals("n_5",batch.answers("receive",answers).getAsJsonObject("item_0").get("choice").getAsString());
        var state=new JsonObject();batch.describe(state);assertFalse(state.get("source_stock_observed").getAsBoolean());assertEquals("minecraft:gold_ingot",state.getAsJsonArray("receive_item_candidates").get(0).getAsString());
    }
    @Test void sourceStacksAndUnknownItemsKeepTheirObservationPhase(){
        var stacks=StartupItemBatch.prepare("取两组鸡蛋",Map.of("minecraft:egg",16),Map.of("minecraft:egg",16),templates());assertFalse(stacks.branches().containsKey("receive"));assertTrue(stacks.branches().containsKey("deliver"));
        assertTrue(StartupItemBatch.prepare("全部金属",Map.of(),Map.of(),templates()).branches().isEmpty());
    }
    @Test void limitsRejectCombinedRequestWithoutTruncatingMandatoryQuestions(){
        var batch=StartupItemBatch.prepare("5个",Map.of("minecraft:gold_ingot",64),Map.of(),templates());var base=new JsonObject();base.add("template",templates());
        var caps=new DecisionProvider.Capabilities(2,255,5,false);assertTrue(StartupItemBatch.fits("mock",new JsonObject(),base,caps));assertFalse(StartupItemBatch.fits("mock",new JsonObject(),batch.append(base),caps));assertEquals(1,base.size());
        var state=new JsonObject();state.addProperty("huge","words ".repeat(40000));assertFalse(StartupItemBatch.fits("mock",state,base,new DecisionProvider.Capabilities(128,255,5,false)));
    }
}
