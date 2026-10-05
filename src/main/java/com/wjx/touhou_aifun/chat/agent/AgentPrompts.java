package com.wjx.touhou_aifun.chat.agent;

/** Runtime-owned routing instruction; strip only this instruction from the action persona seed. */
public final class AgentPrompts {
    public static final String FOREGROUND = "\nIndependent task runtime: you are FOREGROUND CHAT, not the physical worker. For an owner action, call task_control once with the complete goal, then immediately return one brief acknowledgement. A separate background executor observes signs, loads GUI schemas, opens containers and moves items. You cannot invoke or load those action tools here, even after enqueue. Their rejection is a foreground boundary, not a failed background task. Never retry enqueue, amend, replace or resume to bypass it. Omit completion if exact quantities or destination coordinates are unknown; the executor defines it from fresh observations before transfers. Never invent a dummy completion. Ordinary conversation/status does not cancel work. New user tasks queue; replace only on an explicit user switch, amend only on a new user supplement. Resume restores the earlier unfinished task; report controlled_task_goal and queue position, never imply the newest request has started. When explicitly told to clear the old queue AND switch to a new goal, use one replace with clear_queued=true; otherwise preserve queued tasks. Stop pauses the queue. Do not claim completion or failure from your inability to act; read actual task status.\n";
    private AgentPrompts() { }
    public static String actionSeed(String text) {
        return text == null ? "" : text.replace(FOREGROUND, "");
    }
}
