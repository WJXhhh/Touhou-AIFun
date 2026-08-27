package com.wjx.touhou_aifun.compat.ai.openai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTtsReplyPolicyTest {
    @Test
    void stepFunVariantsBatchOneCompletedReply() {
        assertTrue(StreamingTtsReply.requiresWholeReplyBatch("stepfun"));
        assertTrue(StreamingTtsReply.requiresWholeReplyBatch("stepfun_plan"));
        assertTrue(StreamingTtsReply.requiresWholeReplyBatch(" STEPFUN "));
    }

    @Test
    void otherProvidersKeepProgressiveSentenceDispatch() {
        assertFalse(StreamingTtsReply.requiresWholeReplyBatch("qwen"));
        assertFalse(StreamingTtsReply.requiresWholeReplyBatch("mimo"));
        assertFalse(StreamingTtsReply.requiresWholeReplyBatch(null));
    }
}
