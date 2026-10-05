package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import java.util.*;

/** Reject malformed batches before accepting or dispatching any of their actions. */
public final class ToolBatchValidation {
    private ToolBatchValidation() { }
    public static boolean valid(List<ToolCall> calls,Set<String> previousIds) {
        if (calls==null || calls.isEmpty()) return false;
        Set<String> ids=new HashSet<>(previousIds);
        for (ToolCall call:calls) {
            if(call==null || call.getId()==null || call.getId().isBlank() || call.getId().length()>256 || !ids.add(call.getId())
                    || call.getFunction()==null || call.getFunction().getName()==null || call.getFunction().getArguments()==null) return false;
        }
        return true;
    }
}
