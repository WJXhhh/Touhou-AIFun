package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;

/** Capability checks shared by protocol adapters; auxiliary TLM callbacks remain isolated. */
public final class AgentExecution {
    public enum Purpose { CHAT, TASK_EXECUTION, KNOWLEDGE_EXTRACTION, SETTINGS_GENERATION, AUXILIARY }
    public record Context(java.util.UUID maid, long chatTurn, String task, long generation, Purpose purpose) { }
    private static final java.util.Map<Object,Context> CONTEXTS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private AgentExecution() { }
    public static Context context(Object callback) {
        if (!(callback instanceof LLMCallback llm)) return new Context(null,0,null,0,Purpose.AUXILIARY);
        return CONTEXTS.computeIfAbsent(callback,key-> new Context(llm.getMaid().getUUID(),0,null,0,
                callback instanceof TaskCallback ? Purpose.TASK_EXECUTION
                        : callback instanceof com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.AutoGenSettingCallback ? Purpose.SETTINGS_GENERATION
                        : callback instanceof com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.grounded.GroundedAnswerCallback
                          || callback instanceof com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryCallback ? Purpose.KNOWLEDGE_EXTRACTION
                        : callback.getClass()==LLMCallback.class ? Purpose.CHAT : Purpose.AUXILIARY));
    }
    public static void bind(LLMCallback callback, long chatTurn, String task, long generation, Purpose purpose) {
        CONTEXTS.put(callback,new Context(callback.getMaid().getUUID(),chatTurn,task,generation,purpose));
    }
    public static boolean managed(Object callback) { return foreground(callback) || callback instanceof TaskCallback; }
    public static boolean foregroundTool(String id) {
        return java.util.Set.of("task_control", "get_current_datetime", "web_search", "web_fetch", "query_minecraft_wiki", "use_skill", "query_game_context", "load_tool_schema", "read_task_result").contains(id);
    }
    public static boolean foreground(Object callback) { return context(callback).purpose()==Purpose.CHAT; }
    public static boolean task(Object callback) { return callback instanceof TaskCallback; }
}
