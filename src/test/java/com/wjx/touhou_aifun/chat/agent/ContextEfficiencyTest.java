package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.*;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningContentCodec;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextEfficiencyTest {
    static List<LLMMessage> repeatedObservations() {
        var messages=new ArrayList<LLMMessage>();
        messages.add(new LLMMessage(Role.USER,"取五个铁锭，禁止碰金锭。",0));
        messages.add(new LLMMessage(Role.USER,"补充：只用北箱，已拿的两个也计入总数。",0));
        messages.add(new LLMMessage(Role.SYSTEM,"## Current task checkpoint\n{\"completed_net_transfers\":{\"iron\":2},\"mutation_version\":1}",0));
        for(int i=0;i<20;i++) {
            JsonObject scan=new JsonObject();scan.addProperty("status","ok");scan.addProperty("game_tick",i);
            scan.addProperty("completed_tick",i);scan.addProperty("observation_id","scan_"+i);
            scan.addProperty("dimension","minecraft:overworld");scan.addProperty("coverage","visible_candidates_only");
            scan.add("maid_position_at_scan_start",JsonParser.parseString("[10,64,20]"));
            scan.add("important_blocks",JsonParser.parseString("[{\"registry_id\":\"minecraft:chest\",\"position\":[11,64,20]}]"));
            JsonArray blocks=new JsonArray();for(int n=0;n<32;n++) {
                var block=new JsonObject();block.addProperty("registry_id","example:machine_"+n);block.addProperty("status","block");
                block.add("relative_position",JsonParser.parseString("["+n+",0,0]"));blocks.add(block);
            }scan.add("observed_blocks",blocks);scan.addProperty("offset",0);scan.addProperty("has_more",false);
            scan.add("sign_texts",JsonParser.parseString("[{\"text\":\"北箱：只取铁，不要动金\",\"position\":[11,65,20]}]"));
            append(messages,"scan_"+i,"scan_surroundings",ToolResultProjection.project(scan.toString(),"raw_scan_"+i));
            JsonObject menu=new JsonObject();menu.addProperty("snapshot_kind",i%5==0?"full":"delta");
            menu.addProperty("session_id","one-session");menu.addProperty("revision",i+1);menu.addProperty("base_revision",i);
            menu.addProperty("layout","observed widget layout ".repeat(120));menu.addProperty("frame_id",i+1);
            menu.add("slots",JsonParser.parseString("[{\"slot\":0,\"count\":"+(20-i)+",\"item\":\"minecraft:iron_ingot\",\"may_take\":true}]"));
            menu.addProperty("external_transfer",true);menu.addProperty("moved_count",1);menu.addProperty("moved_item","minecraft:iron_ingot");
            menu.addProperty("to_maid",true);menu.addProperty("dimension","minecraft:overworld");menu.add("target_position",JsonParser.parseString("[11,64,20]"));
            append(messages,"action_"+i,"gui_action",ToolResultProjection.project(menu.toString(),"raw_menu_"+i));
        }
        return messages;
    }
    static void append(List<LLMMessage> messages,String id,String tool,String result) {
        messages.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(new ToolCall(id,new FunctionToolCall(tool,"{}"))),null));
        messages.add(new LLMMessage(Role.TOOL,result,0,null,id));
    }
    @Test void measureRepeatedScanAndMenuHistory() {
        var source=repeatedObservations();var result=AgentContext.compact(source,475000,new TaskResultStore());
        assertTrue(result.containsAll(source.subList(0,3)));
        var metrics=new JsonObject();metrics.addProperty("fixture","20_scans_and_20_gui_results");
        metrics.addProperty("input_characters",source.stream().mapToInt(m->m.message().length()).sum());
        metrics.addProperty("output_characters",result.stream().mapToInt(m->m.message().length()).sum());
        metrics.addProperty("input_estimated_tokens",ContextTokenEstimator.estimate(source));
        metrics.addProperty("output_estimated_tokens",ContextTokenEstimator.estimate(result));
        String thinking="Check the exact remaining quantity before choosing an action. ".repeat(200);
        var encoded=new LLMMessage(Role.ASSISTANT,ReasoningContentCodec.encode("Move three iron ingots.",thinking),0);
        metrics.addProperty("encoded_thinking_estimated_tokens",ContextTokenEstimator.estimate(List.of(encoded)));
        System.out.println("CONTEXT_EFFICIENCY "+metrics);
    }

    @Test void deduplicationKeepsEveryReceiptOwnerMessageNewestGeometryAndCallPair() {
        var source=repeatedObservations();var result=AgentContext.compact(source,475000,new TaskResultStore());
        assertTrue(result.containsAll(source.subList(0,3)));
        for(int i=0;i<20;i++) {
            var receipt=jsonResult(result,"action_"+i);
            assertEquals(1,receipt.get("moved_count").getAsInt());assertTrue(receipt.get("external_transfer").getAsBoolean());
            assertEquals("minecraft:iron_ingot",receipt.get("moved_item").getAsString());
            assertTrue(receipt.get("to_maid").getAsBoolean());assertEquals("[11,64,20]",receipt.get("target_position").toString());
            assertEquals("minecraft:overworld",receipt.get("dimension").getAsString());
            if(i<15)assertFalse(receipt.has("slots"));else assertEquals(jsonResult(source,"action_"+i).get("slots"),receipt.get("slots"));
            String callId="action_"+i;
            assertTrue(result.stream().anyMatch(m->m.role()==Role.ASSISTANT && m.toolCalls()!=null && m.toolCalls().get(0).getId().equals(callId)));
        }
        for(int i=0;i<19;i++)assertTrue(jsonResult(result,"scan_"+i).has("same_observation_as"));
        var latest=jsonResult(result,"scan_19");assertEquals(32,latest.getAsJsonArray("observed_blocks").size());
        assertEquals(jsonResult(source,"scan_19").get("sign_texts"),latest.get("sign_texts"));
        assertEquals(latest.get("observation_key"),jsonResult(result,"scan_0").get("same_observation_as"));
        assertEquals(result,AgentContext.compact(result,475000,new TaskResultStore()),"Repeated preparation must not grow references or change detail copies");
    }
    @Test void distinctSignsPositionsPagesCoverageAndErrorsAreNeverTreatedAsDuplicateScans() {
        var base=jsonResult(repeatedObservations(),"scan_0");var messages=new ArrayList<LLMMessage>();
        append(messages,"original","scan_surroundings",base.toString());
        for(String field:List.of("sign_texts","maid_position_at_scan_start","offset","coverage","dimension","error","observed_blocks")) {
            var changed=base.deepCopy();
            switch(field) {
                case "sign_texts" -> changed.add("sign_texts",JsonParser.parseString("[{\"text\":\"只能用红箱\"}]"));
                case "maid_position_at_scan_start" -> changed.add(field,JsonParser.parseString("[11,64,20]"));
                case "offset" -> changed.addProperty(field,32);
                case "observed_blocks" -> changed.getAsJsonArray(field).get(0).getAsJsonObject().addProperty("status","occluded");
                default -> changed.addProperty(field,"changed");
            }
            append(messages,field,"scan_surroundings",changed.toString());
        }
        var result=AgentContext.compact(messages,475000,new TaskResultStore());assertEquals(messages,result);
    }
    @Test void unfinishedBatchesAndDifferentGuiSessionsKeepTheirOriginalSnapshots() {
        var base=jsonResult(repeatedObservations(),"action_0");var messages=new ArrayList<LLMMessage>();
        append(messages,"old-session","inspect_gui",base.toString());
        var next=base.deepCopy();next.addProperty("session_id","different-session");
        append(messages,"new-session","inspect_gui",next.toString());
        messages.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(new ToolCall("pending",new FunctionToolCall("inspect_gui","{}")),new ToolCall("missing",new FunctionToolCall("inspect_gui","{}"))),null));
        messages.add(new LLMMessage(Role.TOOL,base.toString(),0,null,"pending"));
        assertEquals(messages,AgentContext.compact(messages,475000,new TaskResultStore()));
    }
    @Test void unknownReceiptFieldsAndLatestVisualLayoutSurviveGeometryRetirement() {
        var old=jsonResult(repeatedObservations(),"action_0");
        old.add("action_results",JsonParser.parseString("[{\"error\":\"partial\",\"servings_consumed\":1,\"crafts_executed\":2,\"emc_spent\":100,\"plugin_receipt\":{\"unique\":17}}]"));
        old.addProperty("plugin_execution_receipt","must remain");
        old.add("process",JsonParser.parseString("{\"status\":\"UNKNOWN\",\"reason\":\"plugin output cannot yet be verified\",\"reliable\":false}"));
        assertEquals(old.get("process"),JsonParser.parseString(ToolResultProjection.project(old.toString(),"original")).getAsJsonObject().get("process"));
        var newer=old.deepCopy();newer.addProperty("revision",99);newer.addProperty("frame_id",77);
        var messages=new ArrayList<LLMMessage>();append(messages,"old","gui_action",old.toString());append(messages,"new","inspect_gui",newer.toString());
        var result=AgentContext.compact(messages,475000,new TaskResultStore());var retired=jsonResult(result,"old");
        assertEquals(old.get("action_results"),retired.get("action_results"));assertEquals(old.get("plugin_execution_receipt"),retired.get("plugin_execution_receipt"));
        assertEquals(newer,jsonResult(result,"new"));
    }
    @Test void checkpointReferencesOnlyEvidenceAlreadyVisibleAndKeepsUnpairedEvidenceWhole() {
        var evidence=JsonParser.parseString("{\"result_ref\":\"visible\",\"moved_count\":2}").getAsJsonObject();
        var hidden=JsonParser.parseString("{\"result_ref\":\"not-visible\",\"servings_consumed\":1}").getAsJsonObject();
        var messages=List.of(new LLMMessage(Role.TOOL,evidence.toString(),0,null,"call"));
        var projected=TaskContextProjection.recentEvidence(List.of(evidence.toString(),hidden.toString()),messages);
        assertEquals("{\"result_ref\":\"visible\"}",projected.get(0).toString());assertEquals(hidden.toString(),projected.get(1).getAsString());
    }
    @Test void onlyExactRepeatedClosedExplanationsShrinkAndProtocolReasoningStaysIntact() {
        String explanation="I will use the observed chest and check the remaining quantities. ".repeat(30);
        String reasoning="The previous delivery has already committed two iron ingots.";
        var messages=new ArrayList<LLMMessage>();
        messages.add(new LLMMessage(Role.USER,explanation,0));
        for(int i=0;i<3;i++) {
            messages.add(new LLMMessage(Role.ASSISTANT,ReasoningContentCodec.encode(explanation,reasoning),0,
                    List.of(new ToolCall("call_"+i,new FunctionToolCall("query_game_context","{}"))),null));
            if(i<2)messages.add(new LLMMessage(Role.TOOL,"ok",0,null,"call_"+i));
        }
        var projected=AgentContext.compact(messages,475000,new TaskResultStore());
        assertEquals(messages.get(0),projected.get(0));assertEquals(messages.get(3),projected.get(3));assertEquals(messages.get(5),projected.get(5));
        var old=ReasoningContentCodec.decode(projected.get(1).message());assertTrue(old.content().length()<explanation.length());assertEquals(reasoning,old.reasoningContent());
        assertEquals(messages.get(1).toolCalls(),projected.get(1).toolCalls());
    }
    @Test void reasoningEstimateCountsDecodedFieldsAndLeavesLiteralOwnerTextUntouched() {
        for(String thinking:List.of("Check the exact remaining quantity before choosing an action. ".repeat(200),"确认已搬数量，不能重复操作。".repeat(100))) {
            String encoded=ReasoningContentCodec.encode("Move three iron.",thinking);
            assertEquals(ContextTokenEstimator.estimate("Move three iron.")+ContextTokenEstimator.estimate(thinking),
                    ContextTokenEstimator.estimate(List.of(new LLMMessage(Role.ASSISTANT,encoded,0))));
            assertEquals(ContextTokenEstimator.estimate(encoded),ContextTokenEstimator.estimate(List.of(new LLMMessage(Role.USER,encoded,0))));
        }
        String invalid="hello[[TLM_REASONING_CONTENT_BASE64:@@@]]";
        assertEquals(ContextTokenEstimator.estimate(invalid),ContextTokenEstimator.estimate(List.of(new LLMMessage(Role.ASSISTANT,invalid,0))));
        var legacy=new Gson().fromJson("{\"apiType\":\"openai\",\"model\":\"test\",\"factor\":2}",com.wjx.touhou_aifun.chat.context.MaidMemoryState.TokenCalibration.class);
        assertNotEquals(ContextTokenEstimator.VERSION,legacy.normalized().estimatorVersion());
        var current=new com.wjx.touhou_aifun.chat.context.MaidMemoryState.TokenCalibration("openai","test",1.25);
        assertEquals(ContextTokenEstimator.VERSION,new Gson().fromJson(new Gson().toJson(current),current.getClass()).normalized().estimatorVersion());
    }
    static JsonObject jsonResult(List<LLMMessage> messages,String id) {
        return JsonParser.parseString(messages.stream().filter(m->m.role()==Role.TOOL && id.equals(m.toolCallId())).findFirst().orElseThrow().message()).getAsJsonObject();
    }
}
