package com.wjx.touhou_aifun.chat.agent;

import java.util.Locale;
import java.util.Set;

/** Only unambiguous standalone commands bypass the foreground model. */
public final class AgentLocalCommand {
    public enum Action { STOP, PAUSE }
    private static final Set<String> STOP = Set.of("停止", "停下", "别做了", "停止任务", "停止当前任务", "取消任务", "取消当前任务",
            "stop", "stop task", "stop current task", "cancel task", "cancel current task");
    private static final Set<String> PAUSE = Set.of("暂停", "暂停任务", "暂停当前任务", "pause", "pause task", "pause current task");
    private AgentLocalCommand() { }

    public static Action parse(String message) {
        if (message == null) return null;
        String command = message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceFirst("[。.!！]+$", "").trim();
        if (STOP.contains(command)) return Action.STOP;
        if (PAUSE.contains(command)) return Action.PAUSE;
        return null;
    }
}
