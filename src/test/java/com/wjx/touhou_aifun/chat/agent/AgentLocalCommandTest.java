package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentLocalCommandTest {
    @Test void explicitStopIncludesCurrentTaskAndPunctuation() {
        assertEquals(AgentLocalCommand.Action.STOP, AgentLocalCommand.parse("停止当前任务！"));
        assertEquals(AgentLocalCommand.Action.STOP, AgentLocalCommand.parse("  CANCEL   current task. "));
    }
    @Test void pauseRemainsResumableAndDistinctFromStop() {
        assertEquals(AgentLocalCommand.Action.PAUSE, AgentLocalCommand.parse("暂停当前任务。"));
        assertEquals(AgentLocalCommand.Action.PAUSE, AgentLocalCommand.parse("PAUSE"));
    }
    @Test void discussionNegationAndQueueClearStayWithForeground() {
        for (String message : new String[]{"不要停止", "停止？", "你觉得应该停止吗", "stop task after moving the iron", "清空任务", "继续", ""})
            assertNull(AgentLocalCommand.parse(message), message);
        assertNull(AgentLocalCommand.parse(null));
    }
}
