package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient;
import com.wjx.touhou_aifun.config.LLMRuntimeBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class LLMBudgetGameTests {
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void runtimeMixinAllows256RoundsAndStopsAtBudget(GameTestHelper helper) throws Exception {
        var callback = callback(helper);
        for (int round = 0; round < LLMRuntimeBudget.toolRounds(); round++) {
            helper.assertTrue(begin(callback, round), "Allowed tool round " + (round + 1));
        }
        helper.assertTrue(!begin(callback, 10000), "Stops at configured round budget");
        helper.assertTrue(callback.failures == 1, "Exactly one budget error");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void runtimeMixinStillStopsIdenticalStalledBatches(GameTestHelper helper) throws Exception {
        var callback = callback(helper);
        for (int round = 0; round < LLMRuntimeBudget.repeatBatches(); round++) {
            helper.assertTrue(begin(callback, 0), "Allowed repeat " + round);
            callback.addToolResult("{\"error\":\"blocked\",\"game_tick\":" + round + "}", "c1");
        }
        helper.assertTrue(!begin(callback, 0), "Timestamp changes do not bypass repeat guard");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void runtimeMixinAllowsIdenticalBatchesWithProgress(GameTestHelper helper) throws Exception {
        var callback = callback(helper);
        for (int round = 0; round < 32; round++) {
            helper.assertTrue(begin(callback, 0), "Progress allows round " + round);
            callback.addToolResult("{\"remaining\":" + (64 - round) + "}", "c1");
        }
        helper.assertTrue(callback.failures == 0, "No premature repeat error");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void anthropicUsesBudgetAndNeverDispatchesTruncatedTools(GameTestHelper helper) throws Exception {
        var callback = callback(helper);
        callback.needAddTools = false;
        var client = new AnthropicCompatLLMClient(null, null);
        var build = AnthropicCompatLLMClient.class.getDeclaredMethod("buildRequestBody", LLMCallback.class);
        build.setAccessible(true);
        var body = (JsonObject) build.invoke(client, callback);
        helper.assertTrue(body.get("max_tokens").getAsInt() == LLMRuntimeBudget.outputTokens(), "Configured output budget");
        helper.assertTrue(!body.has("thinking"), "Provider thinking mode is preserved");

        var process = AnthropicCompatLLMClient.class.getDeclaredMethod("processResponse", LLMCallback.class,
                JsonObject.class, HttpRequest.class,
                Class.forName("com.wjx.touhou_aifun.compat.ai.openai.StreamingTtsReply"));
        process.setAccessible(true);
        var root = JsonParser.parseString("""
                {"stop_reason":"max_tokens","content":[
                  {"type":"thinking","thinking":"private thought"},
                  {"type":"tool_use","id":"c1","name":"move","input":{"x":1}}]}
                """).getAsJsonObject();
        process.invoke(client, callback, root, null, null);
        helper.assertTrue(callback.toolDispatches == 0 && callback.failures == 1, "Truncated tools cannot execute");
        helper.assertTrue(!callback.lastError.contains("BASE64") && !callback.lastError.contains("private thought"), "No reasoning in player error");
        root.addProperty("stop_reason", "tool_use");
        process.invoke(client, callback, root, null, null);
        helper.assertTrue(callback.toolDispatches == 1 && callback.failures == 1, "Completed tool calls still dispatch");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void anthropicTaskUpdatesPreserveSystemPrefixAndToolPairing(GameTestHelper helper) throws Exception {
        var maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(2,1,2)); maid.setNoAi(true);
        var messages = new ArrayList<com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage>();
        messages.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.systemChat(maid,"Stable task rules"));
        messages.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.userChat(maid,"Take 59 beef"));
        var task = new com.wjx.touhou_aifun.chat.agent.TaskCallback(maid.getAiChatManager(),messages,
                new com.wjx.touhou_aifun.chat.agent.AgentTaskState("Take 59 beef"));
        try {
            var history = task.getMessages();
            var calls = List.of(new Gson().fromJson("{\"id\":\"c1\",\"function\":{\"name\":\"scan_surroundings\",\"arguments\":\"{}\"}}",ToolCall.class),
                    new Gson().fromJson("{\"id\":\"c2\",\"function\":{\"name\":\"query_game_context\",\"arguments\":\"{}\"}}",ToolCall.class));
            history.add(new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage(
                    com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.ASSISTANT,"",0,calls,null));
            history.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.toolChat(maid,"{\"count\":1}","c1"));
            history.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.toolChat(maid,"{\"count\":2}","c2"));
            history.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.systemChat(maid,"## Current task checkpoint\ncount=3 tick=1"));
            var client = new AnthropicCompatLLMClient(null,null);
            var build = AnthropicCompatLLMClient.class.getDeclaredMethod("buildRequestBody",LLMCallback.class); build.setAccessible(true);
            var before=(JsonObject)build.invoke(client,task);
            history.remove(history.size()-1);
            history.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.systemChat(maid,"## Current task checkpoint\ncount=4 tick=2 constraint=leave_one"));
            var after=(JsonObject)build.invoke(client,task);
            helper.assertTrue(before.get("system").equals(after.get("system")),"Changing progress must not rewrite the stable system prefix");
            helper.assertTrue(before.get("tools").equals(after.get("tools")),"Frozen tool definitions keep identical order");
            helper.assertTrue(!after.get("system").getAsString().contains("tick=2"),"Dynamic world facts stay at the message tail");
            var wire=after.getAsJsonArray("messages");var results=wire.get(2).getAsJsonObject().getAsJsonArray("content");
            helper.assertTrue(results.size()==2 && results.get(0).getAsJsonObject().get("tool_use_id").getAsString().equals("c1")
                    && results.get(1).getAsJsonObject().get("tool_use_id").getAsString().equals("c2"),"Parallel tool results remain adjacent and ordered");
            String latest=wire.get(wire.size()-1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
            helper.assertTrue(latest.contains("count=4") && latest.contains("leave_one") && !latest.contains("count=3"),"Only current checkpoint is sent, with owner constraint intact");
            var stream=new com.wjx.touhou_aifun.compat.ai.openai.response.StreamAccumulator();
            var start=AnthropicCompatLLMClient.class.getDeclaredMethod("onMessageStart",JsonObject.class,stream.getClass());start.setAccessible(true);
            var delta=AnthropicCompatLLMClient.class.getDeclaredMethod("onMessageDelta",JsonObject.class,stream.getClass());delta.setAccessible(true);
            start.invoke(client,JsonParser.parseString("{\"message\":{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":2000,\"cache_creation_input_tokens\":0}}}").getAsJsonObject(),stream);
            delta.invoke(client,JsonParser.parseString("{\"usage\":{\"output_tokens\":5}}").getAsJsonObject(),stream);
            helper.assertTrue(stream.buildResponse().getUsage().getTotalTokens()==2015,"Actual SSE adapter keeps input/cache counts when delta contains only output usage");
            helper.succeed();
        } finally { task.operations.cancel(); maid.discard(); }
    }

    private static boolean begin(LLMCallback callback, int argument) throws Exception {
        var method = LLMCallback.class.getDeclaredMethod("beginToolBatch", List.class);
        method.setAccessible(true);
        var call = new Gson().fromJson("{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"move\",\"arguments\":\"{\\\"step\\\":" + argument + "}\"}}", ToolCall.class);
        return (boolean) method.invoke(callback, List.of(call));
    }

    private static CapturingCallback callback(GameTestHelper helper) {
        var maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(2, 1, 2));
        maid.setNoAi(true);
        return new CapturingCallback(maid);
    }

    private static final class CapturingCallback extends LLMCallback {
        int failures;
        int toolDispatches;
        String lastError = "";
        CapturingCallback(EntityMaid maid) { super(maid.getAiChatManager(), new ArrayList<>(), true); }
        @Override public void onFailure(HttpRequest request, Throwable error, int code) { failures++; lastError = error.getMessage(); }
        @Override public void onFunctionCall(Message choice, LLMClient client) { toolDispatches++; }
    }
}
