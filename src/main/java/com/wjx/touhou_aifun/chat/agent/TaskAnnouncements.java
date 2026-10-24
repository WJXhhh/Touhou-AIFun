package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

/**
 * Controls how much task-queue information is announced to the owner.
 *
 * <p>Commands, completion, failure and pause reasons are always facts about the server-side queue. The
 * configured mode only decides what is <em>said out loud</em> (chat bubble, owner chat line and TTS);
 * the full detail is always written to the server log. Explicit questions about a paused/failed task
 * are answered from {@link ForegroundTaskStatus} in every mode and are never reduced to a vague line.</p>
 *
 * <p>In {@code brief} and {@code silent} modes the acknowledgement is not a fixed template: the model
 * phrases one short line from the real dispatch state (starting immediately, waiting for earlier work,
 * paused, amendment, stop) and its persona. {@code detailed} keeps the deterministic legacy receipt.</p>
 */
public final class TaskAnnouncements {
    public enum Mode { detailed, brief, silent }

    private TaskAnnouncements() {
    }

    public static Mode mode() {
        // Config is loaded during mod construction; before that keep the legacy detailed behaviour.
        return TouhouAIFunConfig.SPEC.isLoaded()
                ? parse(TouhouAIFunConfig.AGENT_TASK_ANNOUNCEMENTS.get())
                : Mode.detailed;
    }

    static Mode parse(String value) {
        if (value != null) {
            String trimmed = value.trim();
            for (Mode mode : Mode.values()) {
                if (mode.name().equalsIgnoreCase(trimmed)) return mode;
            }
        }
        return Mode.brief;
    }

    /**
     * Instruction for the model after a task-control receipt. {@code brief}/{@code silent} hand the
     * owner-facing line to the model, but only the model may phrase it: the dispatch state below is
     * authoritative and prevents "starting now" being said while earlier work is still running.
     */
    public static String controlNotice(JsonObject receipt, boolean repeated, Mode mode) {
        if (mode == Mode.detailed) return detailedNotice();
        String dispatch = text(receipt, "controlled_task_dispatch");
        String goal = AgentTaskState.bounded(text(receipt, "controlled_task_goal"), 160);
        String amendment = AgentTaskState.bounded(text(receipt, "accepted_owner_amendment"), 240);
        String guidance = AgentTaskState.bounded(text(receipt, "accepted_owner_guidance"), 240);
        String active = AgentTaskState.bounded(text(receipt, "active_task_goal"), 160);
        StringBuilder facts = new StringBuilder(" Task-control facts: dispatch=")
                .append(dispatch.isBlank() ? "unknown" : dispatch);
        if (!goal.isBlank()) facts.append("; accepted goal=\"").append(goal).append('"');
        if (!amendment.isBlank()) facts.append("; accepted amendment=\"").append(amendment).append('"');
        if (!guidance.isBlank()) facts.append("; accepted guidance=\"").append(guidance).append('"');
        if (!active.isBlank()) facts.append("; executor currently busy with=\"").append(active).append('"');
        facts.append('.');
        String applied = repeated
                ? "This user instruction has already been applied; do not apply it again."
                : "This user instruction has been applied once.";
        if (mode == Mode.silent) {
            return applied + " Physical work runs in a separate background executor." + facts
                    + " Task announcements are silent: do not report completion, failure, queue position, queue counts, status or progress."
                    + " Reply with ONE short, natural, in-character line in the owner's language. Match dispatch:"
                    + " starting_now = you are setting off right now; waiting = you noted it and will do it after the current work;"
                    + " paused = the queue is paused; amendment_next_boundary = briefly confirm the updated detail and that it applies at the next action boundary;"
                    + " amendment_pending = confirm it will apply after continuing; guidance_applied/guidance_next_boundary = briefly confirm the hint is noted and being followed without restating the goal;"
                    + " guidance_pending = confirm it will be used when the task continues; resuming = say you are continuing;"
                    + " stopped/queue_paused = acknowledge briefly; nothing_to_resume = say there is nothing to resume."
                    + " You may restate the gist casually if it feels natural. Do not invoke world/GUI tools or repeat"
                    + " task-control changes. A new owner message is required for another change.";
        }
        return applied + " Physical work runs in a separate background executor; this conversation cannot act on it." + facts
                + " Reply with ONE short, natural, in-character line in the owner's language, matching the real state"
                + " instead of a fixed template. The following are STYLE HINTS, not scripts:"
                + " starting_now -> talk like you are setting off right now (e.g. \"好，我这就去\", \"On it\");"
                + " waiting -> say you noted it and will do it after the current work (e.g. \"记下了，忙完就去\"), and never claim it has started;"
                + " paused -> say the queue is paused until the owner continues;"
                + " completed -> completion is already verified;"
                + " amendment_next_boundary -> briefly confirm the updated detail (e.g. \"了解，改成……是吧\") and that it applies at the next action boundary;"
                + " amendment_pending -> confirm it will apply after continuing;"
                + " guidance_applied/guidance_next_boundary -> briefly confirm the hint is noted and being followed, without restating the goal or calling it a new requirement;"
                + " guidance_pending -> confirm it will be used when the task continues or starts;"
                + " resuming -> say you are continuing the earlier unfinished task;"
                + " stopped/queue_paused -> acknowledge briefly;"
                + " nothing_to_resume -> say there is nothing to resume."
                + " You may restate the gist casually if it feels natural. Keep it to one short line; do not list queue positions,"
                + " queue counts, ids or a verbose status report. Do not invoke world/GUI tools or repeat task-control changes."
                + " A new owner message is required for another change.";
    }

    private static String detailedNotice() {
        return "This user instruction has been applied once. Physical work runs in a separate background task. "
                + "Report the controlled_task_goal, its queue position and paused state honestly. For amend, "
                + "acknowledge accepted_owner_amendment: it overrides conflicting older exclusions while "
                + "preserving other requirements. Resume restores the earliest unfinished task, not the latest "
                + "enqueued goal. If another older task blocks it, say which goal is being resumed; do not "
                + "promise that the new task has started. Do not invoke world/GUI tools or repeat task-control "
                + "changes. A new owner message is required for another change.";
    }

    /** Owner-facing completion/failure announcement; {@code null} means this mode stays silent. */
    public static String outcome(boolean success, boolean english, String goal, String outcome, int queued, Mode mode) {
        if (mode == Mode.silent) return null;
        if (mode == Mode.brief) {
            if (english) return success ? "All done~" : "I got stuck on this one; let's retry later~";
            return success ? "做完啦～" : "这个先卡住啦，等下再弄吧～";
        }
        String queuedNote = queued > 0
                ? english ? " " + queued + " more task(s) are waiting and have not started."
                : " 后续还有" + queued + "个任务等待，尚未执行。"
                : "";
        if (english) {
            return success ? "Task complete: " + outcome
                    : "Task paused [" + AgentTaskState.bounded(goal, 80) + "]: " + outcome + queuedNote;
        }
        return success ? "任务完成：" + outcome
                : "任务已暂停【" + AgentTaskState.bounded(goal, 80) + "】：" + outcome + queuedNote;
    }

    /**
     * Deterministic short fallback for an owner question. The normal path phrases it with the
     * main chat model; this line is used when that side request is unavailable.
     */
    public static String ownerQuestion(JsonObject question, boolean english) {
        StringBuilder line = new StringBuilder();
        String kind = question != null && question.has("kind") ? question.get("kind").getAsString() : "other";
        if (question != null && question.has("facts") && question.get("facts").isJsonObject()) {
            JsonObject facts = question.getAsJsonObject("facts");
            if (facts.has("missing_items") && facts.get("missing_items").isJsonArray()) {
                int shown = 0;
                for (var value : facts.getAsJsonArray("missing_items")) {
                    if (!value.isJsonObject() || shown++ >= 3) break;
                    JsonObject item = value.getAsJsonObject();
                    String name = item.has("display_name") && !item.get("display_name").getAsString().isBlank()
                            ? item.get("display_name").getAsString()
                            : item.has("item") ? item.get("item").getAsString() : "";
                    long need = item.has("required") ? item.get("required").getAsLong() : 0;
                    long have = item.has("have") ? item.get("have").getAsLong() : 0;
                    if (line.length() > 0) line.append(english ? ", " : "、");
                    line.append(name).append(english ? " x" : "×").append(need);
                    if (english) line.append(" (have ").append(have).append(")");
                    else line.append("（有").append(have).append("）");
                }
            }
        }
        if (line.length() == 0) {
            line.append(english ? "something" : "一些材料");
        }
        if ("materials".equals(kind)) {
            return english
                    ? "I'm short of " + line + ". Should I go and find them myself?"
                    : "材料还差" + line + "，要我自己去找吗？";
        }
        return english
                ? "I need your decision before continuing: " + line + ". Should I go ahead?"
                : "继续之前需要你决定一下：" + line + "，要我继续吗？";
    }

    /** Owner-facing pause announcement for a runtime-side failure (model/archive unavailable). */
    public static String runtimePause(boolean invalidModel, boolean english, Mode mode) {        if (mode == Mode.silent) return null;
        if (mode == Mode.brief) {
            if (invalidModel) return english ? "I can't get going right now; let's retry later~" : "我这边暂时使不上劲，回头再试～";
            return english ? "Something glitched on my side; let's retry later~" : "我这出了点小状况，回头再试～";
        }
        if (invalidModel) {
            return english
                    ? "Task paused: the background execution model is unavailable. Check the selected LLM site, model and credentials, then continue."
                    : "任务已暂停：后台执行模型不可用，请检查所选 LLM 站点、模型和凭据后继续。";
        }
        return english
                ? "Task paused: the execution archive is unavailable. Check the world data or archive capacity."
                : "任务已暂停：执行档案不可用，请检查世界数据或档案容量。";
    }

    /** Full task-control detail for the server log, independent of what the owner is told. */
    public static void logControl(String maidId, String action, String taskId, String status, int position,
                                  String goal, boolean queuePaused, String dispatch, Mode mode) {
        TouhouAIFun.LOGGER.info(
                "AIFun task control: maid={} action={} task={} status={} queuePosition={} queuePaused={} dispatch={} announcement={} goal={}",
                maidId, action, taskId == null ? "" : taskId, status == null ? "" : status, position,
                queuePaused, dispatch == null ? "" : dispatch, mode, goal == null ? "" : goal);
    }

    /** Full completion/failure detail for the server log, independent of what the owner is told. */
    public static void logOutcome(String maidId, String taskId, String goal, boolean success, String outcome,
                                  int queued, Mode mode) {
        boolean announced = mode != Mode.silent;
        TouhouAIFun.LOGGER.info(
                "AIFun task outcome: maid={} task={} success={} announced={} announcement={} queued={} goal={} outcome={}",
                maidId, taskId, success, announced, mode, queued, goal, outcome);
    }

    /** Full runtime-pause detail for the server log. */
    public static void logRuntimePause(String maidId, String taskId, String goal, String reason, Mode mode) {
        TouhouAIFun.LOGGER.info(
                "AIFun task paused: maid={} task={} announced={} announcement={} goal={} reason={}",
                maidId, taskId == null ? "" : taskId, mode != Mode.silent, mode, goal, reason);
    }

    private static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
}
