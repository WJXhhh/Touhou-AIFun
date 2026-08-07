package com.wjx.touhou_aifun.chat.context;

/** Access bridge for addon-owned memory attached to a TLM MaidAIChatData instance. */
public interface AIFunMemoryAccess {
    MaidMemoryState touhouAIFun$getMemoryState();

    void touhouAIFun$setMemoryState(MaidMemoryState state);
}
