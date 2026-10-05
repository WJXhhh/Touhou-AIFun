package com.wjx.touhou_aifun.chat.agent;

import java.util.regex.Pattern;

/** Immutable published facts; network/stream threads never read the mutable task queue. */
public record ForegroundTaskStatus(String taskId, AgentTaskState.Status status, String goal,
                                   long movedCount, String outcome) {
    public static final String PROMPT_HEADER="## Runtime task status for foreground\n";
    private static final Pattern QUESTION=Pattern.compile("进度|(?:做完|干完|完成|结束|搞定|弄完)(?:了)?(?:吗|没|没有|了吗|了没|了吧)|(?:放|送|取|搬)(?:进|出|好|完)(?:去|来)?(?:了)?(?:吗|没|了没|吧)|\\b(?:task|job|work)\\b.{0,30}(?:done|finished|complete|progress|status)",Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_SUBJECT=Pattern.compile("任务|这活|工作|箱子|东西|物品|背包|进度|你|\\b(?:task|job|work)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM=Pattern.compile("(?:已经|已|全都|全部|都已|一件都没落).{0,30}(?:完成|放好|放进|送进|归位|取出|搬完|核对|空了|空空)|(?:任务|这活|工作).{0,10}(?:完成了|结束了|搞定了)|(?:all|everything|task|job).{0,35}(?:done|finished|completed|put away|placed|stored|sorted)",Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_CLAIM=Pattern.compile("还没|尚未|没有|未完成|不能|并未|会把|将会|准备|打算|待会|稍后|马上|not |isn't|is not|will |going to",Pattern.CASE_INSENSITIVE);
    public static boolean isQuestion(String text) {
        if(text==null || !QUESTION.matcher(text).find()) return false;
        return TASK_SUBJECT.matcher(text).find() || text.matches("[嗯啊那，,\\s]*(?:做完|干完|完成|结束|搞定|弄完)(?:了)?(?:吗|没|没有|了吗|了没|了吧)[呀呢嘛？?。!！\\s]*");
    }
    public static boolean claimsCompletion(String text) {
        if(text==null) return false;
        for(String sentence:text.split("[。！？!?\\n]"))
            if(CLAIM.matcher(sentence).find() && !NON_CLAIM.matcher(sentence).find()) return true;
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
}
