package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentControlReplyTest {
    private String reply(String json, boolean english) { return AgentControlReply.format(JsonParser.parseString(json).getAsJsonObject(), english); }
    @Test void acceptedQueuedTaskDoesNotPromiseItStarted() {
        var json = "{\"applied_action\":\"enqueue\",\"controlled_task_goal\":\"take iron\",\"controlled_task_status\":\"queued\",\"controlled_task_queue_position\":3}";
        assertTrue(reply(json,false).contains("第 3 位，尚未开始"));
        assertTrue(reply(json,true).contains("not started"));
    }
    @Test void pausedQueueAndResumeNameEarlierTask() {
        assertTrue(reply("{\"applied_action\":\"enqueue\",\"paused\":true}",false).contains("队列已暂停"));
        var resumed = reply("{\"applied_action\":\"resume\",\"controlled_task_goal\":\"old sorting\"}",false);
        assertTrue(resumed.contains("先前未完成") && resumed.contains("old sorting"));
        assertTrue(reply("{\"applied_action\":\"resume\"}",true).contains("no unfinished"));
    }
    @Test void stopAndPauseRetainDistinctSemantics() {
        assertTrue(reply("{\"applied_action\":\"stop\"}",false).contains("取消当前"));
        assertFalse(reply("{\"applied_action\":\"pause\"}",false).contains("取消"));
        assertTrue(reply("{\"applied_action\":\"clear\"}",false).contains("当前及排队"));
    }
    @Test void unsupportedLanguagesKeepTheModelTranslationPath() {
        assertTrue(AgentControlReply.supports("zh_cn"));assertTrue(AgentControlReply.supports("en_us"));
        assertFalse(AgentControlReply.supports("ja_jp"));assertFalse(AgentControlReply.supports(null));
    }
}
