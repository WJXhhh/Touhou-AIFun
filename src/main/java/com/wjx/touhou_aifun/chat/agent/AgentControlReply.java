package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonObject;

/**
 * Legacy deterministic receipt for the detailed announcement mode. The brief mode deliberately keeps
 * the acknowledgement in the model's hands instead: fixed templates read wrong when the maid is idle
 * ("starting now") versus busy ("noted") versus amending ("got it, change X"), so the model phrases it
 * from {@link TaskAnnouncements#controlNotice} and the real dispatch state.
 */
public final class AgentControlReply {
    private AgentControlReply() { }
    public static boolean supports(String language) {
        return language != null && (language.startsWith("zh") || language.startsWith("en"));
    }
    public static String format(JsonObject receipt, boolean english) {
        String action = text(receipt, "applied_action");
        String goal = AgentTaskState.bounded(text(receipt, "controlled_task_goal"), 120);
        String status = text(receipt, "controlled_task_status");
        boolean paused = receipt.has("paused") && receipt.get("paused").getAsBoolean();
        int position = receipt.has("controlled_task_queue_position") ? receipt.get("controlled_task_queue_position").getAsInt() : 0;
        String subject = goal.isBlank() ? "" : english ? " Task: " + goal + "." : "【" + goal + "】";
        if (english) return switch (action) {
            case "enqueue", "replace" -> "Task accepted." + subject + (status.equals("completed") ? " Completion has been verified."
                    : paused ? " The queue is paused; ask to continue." : status.equals("running") || status.equals("waiting")
                    ? " It is running in the background." : " Queue position: " + position + "; it has not started yet.");
            case "amend" -> "Task requirements updated." + subject + (paused ? " The task is paused; ask to continue." : " The executor will apply them at the next action boundary.");
            case "resume" -> goal.isBlank() ? "There are no unfinished tasks to resume." : "Resuming the earlier unfinished task." + subject;
            case "clear" -> "Current and queued tasks cancelled; the queue is paused.";
            case "stop" -> "Current task cancelled; the remaining queue is paused.";
            case "pause" -> "Task queue paused; recorded progress is retained.";
            default -> "Task control applied.";
        };
        return switch (action) {
            case "enqueue", "replace" -> "已接收任务" + subject + "。" + (status.equals("completed") ? "已通过完成核验。"
                    : paused ? "队列已暂停，需要说“继续”才会执行。" : status.equals("running") || status.equals("waiting")
                    ? "正在后台执行。" : "排在第 " + position + " 位，尚未开始执行。");
            case "amend" -> "已更新任务要求" + subject + "。" + (paused ? "任务已暂停，需要说“继续”。" : "会在下一次操作边界应用。");
            case "resume" -> goal.isBlank() ? "没有尚未完成的任务需要恢复。" : "将继续先前未完成的任务" + subject + "。";
            case "clear" -> "已取消当前及排队任务，队列已暂停。";
            case "stop" -> "已取消当前任务，其余队列已暂停。";
            case "pause" -> "已暂停任务队列，保留已有进度。";
            default -> "已应用任务控制。";
        };
    }
    private static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
}
