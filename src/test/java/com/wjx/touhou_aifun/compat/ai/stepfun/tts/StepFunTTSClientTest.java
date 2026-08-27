package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepFunTTSClientTest {
    @Test
    void shortReplyIsNotSplitAgainAtPunctuation() {
        String reply = "(惊讶)视觉失败了。(认真)箱子在左边，苦力怕在前方！";

        assertEquals(List.of(reply), StepFunTTSClient.splitText(reply, 1_000));
    }

    @Test
    void oversizedReplyStillRespectsProviderLimit() {
        String reply = "甲".repeat(700) + "。" + "乙".repeat(700);

        List<String> chunks = StepFunTTSClient.splitText(reply, 1_000);
        assertEquals(2, chunks.size());
        assertEquals(701, chunks.get(0).codePointCount(0, chunks.get(0).length()));
        assertEquals(700, chunks.get(1).codePointCount(0, chunks.get(1).length()));
    }

    @Test
    void stepAudio25UsesCurrentContextualHttpContract() {
        JsonObject body = StepFunTTSClient.requestBody("(激动)你好", "stepaudio-2.5-tts",
                "cixingnansheng", "语速稍快", "mp3");

        assertEquals("stepaudio-2.5-tts", body.get("model").getAsString());
        assertEquals("(激动)你好", body.get("input").getAsString());
        assertEquals("cixingnansheng", body.get("voice").getAsString());
        assertEquals("语速稍快", body.get("instruction").getAsString());
        org.junit.jupiter.api.Assertions.assertFalse(body.has("response_format"));
        org.junit.jupiter.api.Assertions.assertFalse(body.has("sample_rate"));
    }

    @Test
    void legacyStepTtsStillRequestsExplicitMp3() {
        JsonObject body = StepFunTTSClient.requestBody("你好", "step-tts-mini",
                "cixingnansheng", "", "mp3");

        assertEquals("mp3", body.get("response_format").getAsString());
        assertEquals(24_000, body.get("sample_rate").getAsInt());
    }
}
