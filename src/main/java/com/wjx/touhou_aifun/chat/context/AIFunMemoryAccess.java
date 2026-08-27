package com.wjx.touhou_aifun.chat.context;

/** Access bridge for addon-owned memory attached to a TLM MaidAIChatData instance. */
public interface AIFunMemoryAccess {
    String MEMORY_TAG = "TouhouAIFunMemory";
    String MEMORY_BACKUP_TAG = "TouhouAIFunMemoryBackup";

    MaidMemoryState touhouAIFun$getMemoryState();

    void touhouAIFun$setMemoryState(MaidMemoryState state);

    default void touhouAIFun$clearMemoryState() {
        touhouAIFun$setMemoryState(new MaidMemoryState());
    }
}
