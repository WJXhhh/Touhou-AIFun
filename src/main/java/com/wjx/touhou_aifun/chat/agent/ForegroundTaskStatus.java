package com.wjx.touhou_aifun.chat.agent;

import java.util.List;
import java.util.regex.Pattern;

/** Immutable published facts; network/stream threads never read the mutable task queue. */
public record ForegroundTaskStatus(String taskId, AgentTaskState.Status status, String goal,
                                   long movedCount, String outcome, List<String> narration) {
    public ForegroundTaskStatus {
        narration = narration == null ? List.of() : List.copyOf(narration);
    }
    public static final String PROMPT_HEADER="## Runtime task status for foreground\n";
    private static final Pattern QUESTION=Pattern.compile("进度|(?:做完|干完|完成|结束|搞定|弄完)(?:了)?(?:吗|没|没有|了吗|了没|了吧)|(?:放|送|取|搬)(?:进|出|好|完)(?:去|来)?(?:了)?(?:吗|没|了没|吧)|\\b(?:task|job|work)\\b.{0,30}(?:done|finished|complete|progress|status)",Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_SUBJECT=Pattern.compile("任务|这活|工作|箱子|东西|物品|背包|进度|你|\\b(?:task|job|work)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM=Pattern.compile("(?:已经|已|全都|全部|都已|一件都没落).{0,30}(?:完成|放好|放进|送进|归位|取出|搬完|核对|空了|空空)|(?:任务|这活|工作).{0,10}(?:完成了|结束了|搞定了)|(?:all|everything|task|job).{0,35}(?:done|finished|completed|put away|placed|stored|sorted)",Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_CLAIM=Pattern.compile("还没|尚未|没有|未完成|不能|并未|会把|将会|准备|打算|待会|稍后|马上|not |isn't|is not|will |going to",Pattern.CASE_INSENSITIVE);
    private static final Pattern OBSERVATION_DONE=Pattern.compile("(?:已经|已|全部|全都|都已)?(?:(?:完成|结束)(?:了)?(?:扫描|观察|查询)|(?:扫描|观察|查询)(?:已经|已)?(?:完成|结束|完毕))|\\b(?:all\\s+)?(?:scans?|observations?|queries)\\s+(?:are\\s+|were\\s+|is\\s+|have been\\s+|has been\\s+)?(?:done|finished|completed)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern PHYSICAL_CLAIM=Pattern.compile("放好|放进|送进|归位|取出|搬完|整理|合成|stored|sorted|put away|placed|crafted|delivered",Pattern.CASE_INSENSITIVE);
    /** "Why did it stop/fail?" — must be answered with the recorded outcome, never a vague line. */
    private static final Pattern FAILURE_REASON=Pattern.compile(
            "(?:任务|这活|工作|刚才|之前|东西|物品|箱子|你|它|这个).{0,16}(?:失败|没成功|没成|卡住|卡了|卡在|暂停|停了|停下|中断|阻塞|出错|报错|错误|没做完|没完成|完不成|做不到|不行|搞不定|动不了|问题)"
            +"|(?:失败|没成功|没成|卡住|卡了|卡在|暂停|停了|停下|中断|阻塞|出错|报错|没做完|没完成|完不成|做不到|搞不定|动不了).{0,16}(?:为什么|为啥|为何|怎么回事|咋回事|什么原因|啥原因|原因|吗)"
            +"|(?:为什么|为啥|为何|什么原因|啥原因|怎么回事|咋回事).{0,16}(?:失败|没成功|没成|卡住|暂停|中断|阻塞|出错|报错|没做完|没完成|完不成|做不到|搞不定|动不了|错误)"
            +"|\\b(?:why|what happened|how come)\\b.{0,32}(?:\\b(?:fail(?:ed|s|ure)?|stuck|paused|blocked|stopped|error(?:ed)?|not (?:done|finished|completed)|couldn'?t|can'?t)\\b)"
            +"|\\b(?:fail(?:ed|s|ure)?|stuck|paused|blocked|stopped|error(?:ed)?)\\b.{0,32}\\b(?:why|reason)\\b",
            Pattern.CASE_INSENSITIVE);
    public static boolean isQuestion(String text) {
        if(text==null || !QUESTION.matcher(text).find()) return false;
        return TASK_SUBJECT.matcher(text).find() || text.matches("[嗯啊那，,\\s]*(?:做完|干完|完成|结束|搞定|弄完)(?:了)?(?:吗|没|没有|了吗|了没|了吧)[呀呢嘛？?。!！\\s]*");
    }
    public static boolean asksFailureReason(String text) {
        return text != null && FAILURE_REASON.matcher(text).find();
    }
    public static boolean claimsCompletion(String text) {
        if(text==null) return false;
        for(String sentence:text.split("[。！？!?\\n]")) {
            // A new foreground scan can finish while an older physical job is paused.
            // Keep compound statements claiming item work subject to the original guard.
            String candidate=PHYSICAL_CLAIM.matcher(sentence).find()?sentence:OBSERVATION_DONE.matcher(sentence).replaceAll("");
            if(CLAIM.matcher(candidate).find() && !NON_CLAIM.matcher(sentence).find()) return true;
        }
        return false;
    }
    public String reply(boolean english) {
        if(english) return switch(status) {
            case completed -> "The task has passed runtime completion verification.";
            case paused,failed -> "The task is paused or blocked; completion has not been verified. Recorded item transfers: "+movedCount+". Review the unmet requirements before continuing.";
            case cancelled -> "The task was cancelled; it has not been reported as completed.";
            case queued -> "The task is queued and has not started.";
            default -> "The background task is still running. Recorded item transfers: "+movedCount+". Final completion has not been verified.";
        };
        return switch(status) {
            case completed -> "这项任务已通过运行时完成核验。";
            case paused,failed -> "这项任务已暂停或遇到阻塞，尚未通过完成核验。已有 "+movedCount+" 件物品的转移记录。"+AgentTaskState.bounded(outcome,512);
            case cancelled -> "这项任务已取消，没有申报完成。";
            case queued -> "这项任务仍在排队，尚未开始执行。";
            default -> "后台任务仍在执行，已有 "+movedCount+" 件物品的转移记录；尚未通过最终完成核验。";
        };
    }
    /**
     * Explicit reason for an owner who asked why the task stopped. Never replaced by a vague line,
     * regardless of the announcement mode; when nothing was recorded that is stated honestly.
     */
    public String failureReply(boolean english) {
        String reason = AgentTaskState.bounded(outcome, 1024);
        String task = AgentTaskState.bounded(goal, 160);
        String state = status == AgentTaskState.Status.failed ? (english ? "failed" : "失败") : (english ? "paused" : "暂停");
        if (english) return reason.isBlank()
                ? "The task [" + task + "] is " + state + ", but no failure reason was recorded; it may have been paused, reloaded or stopped by the owner."
                : "The task [" + task + "] is " + state + ". Recorded reason: " + reason;
        return reason.isBlank()
                ? "任务【" + task + "】已" + state + "，但没有记录到具体失败原因；可能是被暂停、重新载入或主人停止。"
                : "任务【" + task + "】已" + state + "，失败原因是：" + reason;
    }
}
