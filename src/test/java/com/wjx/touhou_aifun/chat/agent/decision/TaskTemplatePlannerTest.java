package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskTemplatePlannerTest {
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static JsonObject answers(String scope,String... amounts){var a=json("{\"coverage\":{\"noul\":0.99},\"quantity_scope\":{\"choice\":\""+scope+"\",\"confidence\":0.99}}");for(int i=0;i<amounts.length;i++)a.add("item_"+i,json("{\"choice\":\""+amounts[i]+"\",\"confidence\":0.99}"));return a;}
    private static ObservedTargetCatalog.Target target(int x){return new ObservedTargetCatalog.Target("b"+x,"container",json("{\"position\":["+x+",64,0],\"dimension\":\"minecraft:overworld\",\"registry_id\":\"minecraft:chest\"}"),new JsonObject());}
    @Test void quantitiesComeOnlyFromExplicitLiteralsIncludingStackUnits(){assertEquals(Set.of(20,128,5),TaskTemplatePlanner.literalQuantities("拿20个铁锭、两组金锭，再取五个钻石"));assertEquals(Set.of(128,5),TaskTemplatePlanner.literalQuantities("take two stacks of iron and five gold"));assertTrue(TaskTemplatePlanner.literalQuantities("拿一些铁锭").isEmpty());}
    @Test void stackQuantitiesUseActualItemLimitsAndRejectAmbiguousLimits(){
        assertEquals(Set.of(32),TaskTemplatePlanner.literalQuantities("拿两组鸡蛋",16));
        assertEquals(Set.of(2),TaskTemplatePlanner.literalQuantities("take two stacks of swords",1));
        assertTrue(TaskTemplatePlanner.literalQuantities("拿两组鸡蛋",0).isEmpty());
        var selected=new TaskTemplatePlanner.Selection("receive",null,List.of(target(1)));
        var plan=TaskTemplatePlanner.transfers(selected,List.of("minecraft:egg"),answers("total","n_32"),List.of(Map.of("minecraft:egg",48L)),Map.of(),"拿两组鸡蛋",.9,Map.of("minecraft:egg",16));
        assertNotNull(plan);assertEquals(32,plan.completion().getAsJsonArray("conditions").get(0).getAsJsonObject().get("count").getAsInt());
        assertNull(TaskTemplatePlanner.transfers(selected,List.of("minecraft:egg"),answers("total","n_128"),List.of(Map.of("minecraft:egg",160L)),Map.of(),"拿两组鸡蛋",.9,Map.of("minecraft:egg",16)));
    }
    @Test void compoundEnglishNumbersStayWholeAndAmbiguousChineseAmountsDelegate(){
        assertEquals(Set.of(25,164),TaskTemplatePlanner.literalQuantities("take twenty-five gold and one hundred and sixty-four iron"));
        assertTrue(TaskTemplatePlanner.literalQuantities("拿一百五个铁锭").isEmpty());
        assertEquals(Set.of(105),TaskTemplatePlanner.literalQuantities("拿一百零五个铁锭"));
    }
    @Test void eachContainerCountsAndExcludedItemsProduceACompleteLiveBoundPlan(){
        var selected=new TaskTemplatePlanner.Selection("receive",null,List.of(target(1),target(2)));
        var plan=TaskTemplatePlanner.transfers(selected,List.of("minecraft:gold_ingot","minecraft:beef"),answers("per_target","n_5","skip"),List.of(Map.of("minecraft:gold_ingot",8L,"minecraft:beef",4L),Map.of("minecraft:gold_ingot",5L,"minecraft:beef",8L)),Map.of(),"每箱取5个金锭，牛肉不动",.9);
        assertNotNull(plan);var conditions=plan.completion().getAsJsonArray("conditions");assertEquals(2,conditions.size());assertEquals(6,plan.steps().size());
        for(var c:conditions){assertEquals(5,c.getAsJsonObject().get("count").getAsInt());assertEquals("minecraft:gold_ingot",c.getAsJsonObject().get("item").getAsString());}
        assertTrue(plan.steps().toString().contains("$live.transfer_count_1"));
        var open=ActionPlan.parse(plan.steps().get(0).getAsJsonObject());assertNull(open.until());assertEquals(conditions.get(0),open.skipIf());
    }
    @Test void totalQuantityIsAllocatedOnlyAcrossSelectedSources(){
        var selected=new TaskTemplatePlanner.Selection("receive",null,List.of(target(1),target(2)));
        var plan=TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("total","n_5"),List.of(Map.of("minecraft:iron_ingot",2L),Map.of("minecraft:iron_ingot",10L)),Map.of(),"取5个铁锭",.9);
        assertNotNull(plan);var c=plan.completion().getAsJsonArray("conditions");assertEquals(2,c.get(0).getAsJsonObject().get("count").getAsInt());assertEquals(3,c.get(1).getAsJsonObject().get("count").getAsInt());
    }
    @Test void unknownOrInventedAmountsAndMissingSupplyDelegateInsteadOfChangingTheGoal(){
        var selected=new TaskTemplatePlanner.Selection("receive",null,List.of(target(1)));var stocks=List.of(Map.of("minecraft:iron_ingot",4L));
        assertNull(TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("total","unknown"),stocks,Map.of(),"取一些铁锭",.9));
        assertNull(TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("total","n_3"),stocks,Map.of(),"取5个铁锭",.9));
        assertNull(TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("total","n_5"),stocks,Map.of(),"取5个铁锭",.9));
    }
    @Test void allUsesObservedStockRatherThanAChosenNumber(){var selected=new TaskTemplatePlanner.Selection("receive",null,List.of(target(1)));var plan=TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("total","all"),List.of(Map.of("minecraft:iron_ingot",9L)),Map.of(),"取全部铁锭",.9);assertNotNull(plan);assertEquals(9,plan.completion().getAsJsonArray("conditions").get(0).getAsJsonObject().get("count").getAsInt());}
    @Test void depositCountsCannotExceedTheBackpackOrMultiplyAllAcrossDestinations(){
        var selected=new TaskTemplatePlanner.Selection("deliver",null,List.of(target(1),target(2)));var stocks=List.<Map<String,Long>>of(Map.of(),Map.of());
        assertNull(TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("per_target","n_5"),stocks,Map.of("minecraft:iron_ingot",8L),"每箱放5个铁锭",.9));
        assertNull(TaskTemplatePlanner.transfers(selected,List.of("minecraft:iron_ingot"),answers("per_target","all"),stocks,Map.of("minecraft:iron_ingot",8L),"每箱放全部铁锭",.9));
    }
    @Test void lowConfidenceNeverInstallsACompletionContract(){var a=answers("total","n_5");a.getAsJsonObject("item_0").addProperty("confidence",.5);assertNull(TaskTemplatePlanner.transfers(new TaskTemplatePlanner.Selection("receive",null,List.of(target(1))),List.of("minecraft:iron_ingot"),a,List.of(Map.of("minecraft:iron_ingot",8L)),Map.of(),"5个铁锭",.9));}
    @Test void rebuildPreservesEveryConditionAndDelegatesUnsupportedOutcomes(){
        var completion=json("{\"kind\":\"all\",\"conditions\":[{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"position\":[1,64,0],\"item\":\"minecraft:iron_ingot\",\"count\":5,\"to_maid\":true},{\"kind\":\"maid_state\",\"dimension\":\"minecraft:overworld\",\"state\":\"sit\",\"value\":\"true\"}]}");
        var plan=TaskTemplatePlanner.fromCompletion(completion);assertNotNull(plan);assertEquals(completion,plan.completion());assertEquals(4,plan.steps().size());assertTrue(plan.steps().toString().contains("switch_sit"));
        assertNull(TaskTemplatePlanner.fromCompletion(json("{\"kind\":\"position\",\"dimension\":\"minecraft:overworld\",\"position\":[1,64,0]}")));
    }
}
