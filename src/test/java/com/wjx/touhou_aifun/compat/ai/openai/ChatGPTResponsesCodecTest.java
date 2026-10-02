package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.FunctionToolCall;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTReasoningSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTResponsesCodecTest {
    @Test void nativeSearchCoexistsWithMaidActionsWithoutExposingDeepSeekSearch() {
        JsonArray functions = new JsonArray();
        functions.add(json("{\"type\":\"function\",\"name\":\"web_search\"}"));
        functions.add(json("{\"type\":\"function\",\"name\":\"current_date_time\"}"));
        var body = ChatGPTResponsesCodec.request("gpt-6.1-sol", List.of(), List.of(), functions,
                ChatGPTReasoningSettings.DEFAULT, true);
        var tools = body.getAsJsonArray("tools");
        assertEquals("web_search", tools.get(0).getAsJsonObject().get("type").getAsString());
        var maid = tools.get(1).getAsJsonObject();
        assertEquals("maid", maid.get("name").getAsString());
        assertEquals(1, maid.getAsJsonArray("tools").size());
        assertEquals("current_date_time", maid.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(2, functions.size());
        assertTrue(body.getAsJsonArray("input").toString().contains("ChatGPT subscription"));
        assertTrue(body.get("stream").getAsBoolean());
        assertFalse(body.get("store").getAsBoolean());
        assertEquals("web_search_call.action.sources", body.getAsJsonArray("include").get(0).getAsString());
    }

    @Test void disablingSearchCannotSilentlyUseTheOtherSearchProvider() {
        JsonArray functions = new JsonArray(); functions.add(json("{\"type\":\"function\",\"name\":\"web_search\"}"));
        var disabled = ChatGPTResponsesCodec.request("gpt-6-sol", List.of(), List.of(), functions,
                ChatGPTReasoningSettings.DEFAULT, false);
        assertFalse(disabled.has("tools"));
        assertFalse(disabled.has("include"));
        var enabled = ChatGPTResponsesCodec.request("gpt-6-sol", List.of(), List.of(), new JsonArray(),
                ChatGPTReasoningSettings.DEFAULT, true);
        assertEquals(1, enabled.getAsJsonArray("tools").size());
    }

    @Test void hostedSearchIsNotDispatchedAsAMaidFunctionAndKeepsCitationsFromThinTerminalEvent() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.web_search_call.searching\"}"));
        assertTrue(events.isSearching()); assertTrue(events.hasSearch()); assertFalse(events.hasTools());
        assertThrows(IllegalStateException.class, events::sources);
        events.accept(json("{\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\",\"title\":\"Forge\",\"url\":\"https://files.minecraftforge.net/\"}}"));
        events.accept(json("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"web_search_call\",\"status\":\"completed\"}}"));
        events.accept(json("{\"type\":\"response.output_text.done\",\"text\":\"Recommended version. ([Forge](https://files.minecraftforge.net/))\"}"));
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"));
        assertFalse(events.isSearching()); assertFalse(events.hasTools());
        assertEquals(1, events.sources().size());
        assertEquals("https://files.minecraftforge.net/", events.sources().get(0).url());
        var message = OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice();
        assertFalse(message.hasToolCall());
        assertEquals("Recommended version.", message.getVisibleContent());
    }

    @Test void fabricatedLegacySearchFunctionCannotExecuteAgainstDeepSeek() {
        var events = new ChatGPTResponsesCodec.Events();
        assertThrows(IllegalStateException.class, () -> events.accept(json("""
                {"type":"response.completed","response":{"status":"completed","output":[
                {"type":"function_call","namespace":"maid","name":"web_search","call_id":"wrong","arguments":"{}"}]}}
                """)));
        assertFalse(events.isCompleted());
    }

    @Test void terminalSearchFeedIsVisibleWithoutTextCitationAnnotations() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("""
                {"type":"response.completed","response":{"status":"completed","output":[
                {"type":"web_search_call","status":"completed","action":{"type":"search","sources":[{"type":"api","name":"oai-weather"}]}},
                {"type":"message","content":[{"type":"output_text","text":"Real weather forecast"}]}]}}
                """));
        assertTrue(events.hasSearch());
        assertFalse(events.hasTools());
        assertEquals("oai-weather", events.sources().get(0).title());
        assertEquals("Real weather forecast", OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice().getVisibleContent());
    }

    @Test void subscriptionRequestIncludesSelectedReasoningPreferences() {
        var body = ChatGPTResponsesCodec.request("gpt-6.1-sol", List.of(new LLMMessage(Role.USER, "Hello", 0)),
                List.of(), new JsonArray(), new ChatGPTReasoningSettings(true, "high"));
        assertEquals("auto", body.getAsJsonObject("reasoning").get("summary").getAsString());
        assertEquals("high", body.getAsJsonObject("reasoning").get("effort").getAsString());
        assertFalse(body.get("store").getAsBoolean());
        assertTrue(body.get("stream").getAsBoolean());
        var disabled = ChatGPTResponsesCodec.request("gpt-6.1-sol", List.of(), List.of(), new JsonArray(), new ChatGPTReasoningSettings(false, "default"));
        assertFalse(disabled.has("reasoning"));
    }

    @Test void summaryCannotMaskMissingFinalTextAfterStreaming() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"Summary\"}"));
        events.accept(json("{\"type\":\"response.output_text.delta\",\"delta\":\"Final answer\"}"));
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Summary\"}]}]}}"));
        var message = OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice();
        assertEquals("Final answer", message.getVisibleContent());
        assertEquals("Summary", message.getReasoningContent());
    }

    @Test void lightweightTerminalEnvelopeKeepsReasoningSeparateFromSpokenAnswer() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"Partial summary\"}"));
        events.accept(json("{\"type\":\"response.reasoning_summary_text.done\",\"text\":\"Complete summary\"}"));
        events.accept(json("{\"type\":\"response.output_text.delta\",\"delta\":\"Hello!\"}"));
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"));
        var message = OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice();
        assertEquals("Hello!", message.getVisibleContent());
        assertEquals("Complete summary", message.getReasoningContent());
        assertEquals("Partial summary", events.partial().currentReasoning());
    }

    @Test void preservesAgentHistoryAndUsesSubscriptionCompatibleFields() {
        JsonArray functions = new JsonArray(); functions.add(json("{\"type\":\"function\",\"name\":\"web_fetch\"}"));
        var body = ChatGPTResponsesCodec.request("available-model", List.of(
                new LLMMessage(Role.SYSTEM, "maid setting", 0),
                new LLMMessage(Role.USER, "search", 0),
                new LLMMessage(Role.ASSISTANT, "", 0, List.of(new ToolCall("call-1", new FunctionToolCall("web_search", "{}"))), null),
                new LLMMessage(Role.TOOL, "found", 0, null, "call-1")), List.of("format reminder"), functions);
        assertTrue(body.get("stream").getAsBoolean()); assertFalse(body.get("store").getAsBoolean());
        assertFalse(body.has("max_output_tokens")); assertFalse(body.has("temperature"));
        var input = body.getAsJsonArray("input");
        assertEquals("developer", input.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("maid", input.get(2).getAsJsonObject().get("namespace").getAsString());
        assertEquals("call-1", input.get(3).getAsJsonObject().get("call_id").getAsString());
        assertEquals("namespace", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test void refusesTruncatedFailedAndIncompleteStreamsAfterPartialText() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"));
        assertEquals("hello", events.partial().currentContent());
        assertThrows(IllegalStateException.class, events::completed);
        var failure = assertThrows(IllegalStateException.class, () -> events.accept(json("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}")));
        assertTrue(failure.getMessage().contains("settings/usage"));
        assertThrows(IllegalStateException.class, () -> new ChatGPTResponsesCodec.Events().accept(json("{\"type\":\"response.incomplete\"}")));
    }

    @Test void executesOnlyCompletedToolsAndKeepsCallIdsAndUsage() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\"}}"));
        assertTrue(events.hasTools()); assertFalse(events.isCompleted());
        events.accept(json("""
                {"type":"response.completed","response":{"status":"completed","output":[
                {"type":"function_call","namespace":"maid","call_id":"call-2","name":"web_fetch","arguments":"{}"}],
                "usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15}}}
                """));
        var response = OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString());
        assertEquals("call-2", response.getFirstChoice().getToolCalls().get(0).getId());
        assertEquals(15, response.getUsage().getTotalTokens());
    }

    @Test void terminalEnvelopeDoesNotEraseStreamedReply() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.output_text.delta\",\"delta\":\"你好！\"}"));
        events.accept(json("{\"type\":\"response.output_text.delta\",\"delta\":\"---Hello!\"}"));
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":6165,\"output_tokens\":185}}}"));
        var response = OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString());
        assertEquals("你好！---Hello!", response.getFirstChoice().getContent());
        assertEquals(6350, response.getUsage().getTotalTokens());
    }

    @Test void finishedTextCanSupplyReplyWithoutDeltas() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.output_text.done\",\"output_index\":0,\"content_index\":0,\"text\":\"完整回复\"}"));
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"));
        assertEquals("完整回复", OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice().getContent());
    }

    @Test void finishedToolIsRetainedButCannotExecuteBeforeCompletion() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"namespace\":\"maid\",\"call_id\":\"call-done\",\"name\":\"web_fetch\",\"arguments\":\"{}\"}}"));
        assertThrows(IllegalStateException.class, events::completed);
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"));
        assertEquals("call-done", OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice().getToolCalls().get(0).getId());
    }

    @Test void authoritativeTerminalTextOverridesPartialAndDoneText() {
        var events = new ChatGPTResponsesCodec.Events();
        events.accept(json("{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}"));
        events.accept(json("{\"type\":\"response.output_text.done\",\"text\":\"done\"}"));
        events.accept(json("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final\"}]}]}}"));
        assertEquals("final", OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getFirstChoice().getContent());
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
