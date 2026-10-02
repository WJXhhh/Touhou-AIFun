package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepFunTTSClientTest {
    @Test
    void singingMarkerJoinsFirstLyricButPreservesLaterLyricLines() {
        for (String model : List.of("stepaudio-3-tts", "stepaudio-2.5-tts")) {
            for (String marker : List.of("(唱歌)", "（唱歌）")) {
                String input = marker + "\r\n\r\n　星光落在窗。\n明天再出发。";
                JsonObject body = StepFunTTSClient.requestBody(input, model, "yuanqishaonv", "", "mp3");
                assertEquals(marker + "星光落在窗。\n明天再出发。", body.get("input").getAsString());
            }
        }
    }

    @Test
    void ordinaryTextAndInlineSingingMentionKeepTheirLineBreaks() {
        for (String input : List.of("(温柔)\n你好。\n再见。", "这个标记是(唱歌)\n不是歌词。", "(唱歌)星光。\n山岗。")) {
            JsonObject body = StepFunTTSClient.requestBody(input, "stepaudio-3-tts", "yuanqishaonv", "", "mp3");
            assertEquals(input, body.get("input").getAsString());
        }
    }

    @Test
    void singingCueOverridesConflictingSpeakingInstructionOnlyForThatReply() {
        String preset = "用平稳语气朗读，不要唱歌";
        String singing = StepFunTTSClient.synthesisInstruction("stepaudio-3-tts", preset, "(唱歌)星光照山岗");
        org.junit.jupiter.api.Assertions.assertTrue(singing.contains("清唱"));
        org.junit.jupiter.api.Assertions.assertFalse(singing.contains(preset));
        assertEquals(preset, StepFunTTSClient.synthesisInstruction("stepaudio-3-tts", preset, "我们聊聊唱歌吧"));
        assertEquals(preset, StepFunTTSClient.synthesisInstruction("stepaudio-3-tts", preset, "请不要写(唱歌)标签"));
        assertEquals(singing, StepFunTTSClient.synthesisInstruction("stepaudio-2.5-tts", preset, " （唱歌）星光照山岗"));
        assertEquals("", StepFunTTSClient.synthesisInstruction("step-tts-2", preset, "(唱歌)星光照山岗"));
    }

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
        assertEquals("mp3", body.get("response_format").getAsString());
        assertEquals(24_000, body.get("sample_rate").getAsInt());
    }

    @Test
    void stepAudio3UsesMp3AndRetainsContextualInstructions() {
        String instruction = "温柔".repeat(250);
        JsonObject body = StepFunTTSClient.requestBody("(高兴)你好", "stepaudio-3-tts",
                "yuanqishaonv", StepFunTTSClient.synthesisInstruction("stepaudio-3-tts", instruction), "mp3");

        assertEquals("stepaudio-3-tts", body.get("model").getAsString());
        assertEquals(instruction, body.get("instruction").getAsString());
        assertEquals("mp3", body.get("response_format").getAsString());
        assertEquals(24_000, body.get("sample_rate").getAsInt());
        org.junit.jupiter.api.Assertions.assertFalse(body.has("voice_label"));
    }

    @Test
    void instructionLimitsRespectModelAndUnicodeCodePoints() {
        String instruction = "温柔😀".repeat(200);
        String modern = StepFunTTSClient.synthesisInstruction("stepaudio-3-tts", instruction);
        String older = StepFunTTSClient.synthesisInstruction("stepaudio-2.5-tts", instruction);

        assertEquals(500, modern.codePointCount(0, modern.length()));
        assertEquals(200, older.codePointCount(0, older.length()));
        assertEquals("", StepFunTTSClient.synthesisInstruction("step-tts-mini", instruction));
    }

    @Test
    void legacyStepTtsStillRequestsExplicitMp3() {
        JsonObject body = StepFunTTSClient.requestBody("你好", "step-tts-mini",
                "cixingnansheng", "", "mp3");

        assertEquals("mp3", body.get("response_format").getAsString());
        assertEquals(24_000, body.get("sample_rate").getAsInt());
    }
}
