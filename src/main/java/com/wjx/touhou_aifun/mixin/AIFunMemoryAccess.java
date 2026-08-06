package com.wjx.touhou_aifun.mixin;

import com.wjx.touhou_aifun.chat.context.MaidMemoryState;

/** Access bridge for the addon-owned memory attached to a TLM MaidAIChatData instance. */
public interface AIFunMemoryAccess {
    MaidMemoryState touhouAIFun$getMemoryState();

    void touhouAIFun$setMemoryState(MaidMemoryState state);
}
