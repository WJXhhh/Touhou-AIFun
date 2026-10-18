package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepFunTTSStreamTest {
    private static StepFunTTSSite site(String url) {
        return new StepFunTTSSite("stepfun", null, url, true, "test-key",
                "", "", "", Map.of(), Map.of());
    }

    @Test
    void officialSpeechUrlMapsToRealtimeEndpoint() {
        assertEquals("wss://api.stepfun.com/v1/realtime/audio?model=stepaudio-3-tts",
                StepFunTTSStream.realtimeUri("https://api.stepfun.com/v1/audio/speech", "stepaudio-3-tts").toString());
    }

    @Test
    void stepPlanSiteKeepsItsPrefix() {
        assertEquals("wss://api.stepfun.com/step_plan/v1/realtime/audio?model=stepaudio-2.5-tts",
                StepFunTTSStream.realtimeUri("https://api.stepfun.com/step_plan/v1/audio/speech",
                        "stepaudio-2.5-tts").toString());
    }

    @Test
    void customPortAndPlainWebSocketSchemeArePreserved() {
        assertEquals("wss://api.stepfun.com:8443/v1/realtime/audio?model=step-tts-2",
                StepFunTTSStream.realtimeUri("https://api.stepfun.com:8443/v1/audio/speech",
                        "step-tts-2").toString());
        assertEquals("ws://localhost:8080/v1/realtime/audio?model=step-tts-mini",
                StepFunTTSStream.realtimeUri("http://localhost:8080/v1/audio/speech",
                        "step-tts-mini").toString());
    }

    @Test
    void onlyDocumentedModelsUseTheStreamingEndpoint() {
        for (String model : List.of("stepaudio-3-tts", "stepaudio-2.5-tts", "step-tts-2",
                "step-tts-mini", "step-tts-vivid")) {
            assertTrue(StepFunTTSStream.supportsModel(model), model);
        }
        assertFalse(StepFunTTSStream.supportsModel("step-router-v1"));
        assertFalse(StepFunTTSStream.supportsModel("stepaudio-3-tts:voice"));
        assertFalse(StepFunTTSStream.supportsModel(null));
    }

    @Test
    void streamingRequiresTheOfficialHost() {
        assertTrue(StepFunTTSClient.supportsStreaming("https://api.stepfun.com/v1/audio/speech", "stepaudio-3-tts"));
        assertTrue(StepFunTTSClient.supportsStreaming("https://api.stepfun.com/step_plan/v1/audio/speech",
                "stepaudio-2.5-tts"));
        assertFalse(StepFunTTSClient.supportsStreaming("https://example.com/v1/audio/speech", "stepaudio-3-tts"));
        assertFalse(StepFunTTSClient.supportsStreaming("https://api.stepfun.com/v1/audio/speech", "unknown-tts"));
    }

    @Test
    void textEventsCarrySessionAndText() {
        JsonObject delta = StepFunTTSStream.textDeltaEvent("session-1", "你好。");
        assertEquals("tts.text.delta", delta.get("type").getAsString());
        assertEquals("session-1", delta.getAsJsonObject("data").get("session_id").getAsString());
        assertEquals("你好。", delta.getAsJsonObject("data").get("text").getAsString());

        JsonObject done = StepFunTTSStream.textDoneEvent("session-1");
        assertEquals("tts.text.done", done.get("type").getAsString());
        assertEquals("session-1", done.getAsJsonObject("data").get("session_id").getAsString());
    }

    @Test
    void textSplittingNeverCutsSurrogatePairs() {
        String emoji = "😀";
        String text = emoji.repeat(1_500);
        List<String> chunks = StepFunTTSStream.splitText(text, StepFunTTSStream.MAX_TEXT_CODE_POINTS);
        assertEquals(2, chunks.size());
        assertEquals(1_000, chunks.get(0).codePointCount(0, chunks.get(0).length()));
        assertEquals(500, chunks.get(1).codePointCount(0, chunks.get(1).length()));
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(StepFunTTSStream.splitText("", 1_000).isEmpty());
        assertTrue(StepFunTTSStream.splitText(null, 1_000).isEmpty());
    }

    @Test
    void resolveSpeechDecodesVoicePresetAndStepPlanOverride() {
        StepFunTTSClient.SpeechSpec spec = StepFunTTSClient.resolveSpeech(
                site("https://api.stepfun.com/v1/audio/speech"),
                new TTSConfig("stepaudio-3-tts:yuanqishaonv", "zh"), "你好。");
        assertEquals("stepaudio-3-tts", spec.model());
        assertEquals("yuanqishaonv", spec.voice());
        assertEquals("", spec.instruction());

        StepFunTTSClient.SpeechSpec plan = StepFunTTSClient.resolveSpeech(
                site("https://api.stepfun.com/step_plan/v1/audio/speech"),
                new TTSConfig("stepaudio-3-tts:yuanqishaonv", "zh"), "你好。");
        assertEquals("stepaudio-2.5-tts", plan.model());
    }

    @Test
    void singingCueTurnsIntoWholeReplySingingInstruction() {
        StepFunTTSClient.SpeechSpec spec = StepFunTTSClient.resolveSpeech(
                site("https://api.stepfun.com/v1/audio/speech"),
                new TTSConfig("stepaudio-3-tts:yuanqishaonv", "zh"), "(唱歌)星光落在窗。");
        assertTrue(spec.instruction().contains("清唱"));
        assertTrue(StepFunTTSClient.isSingingRequest("（唱歌）星光落在窗。"));
        assertFalse(StepFunTTSClient.isSingingRequest("这个标记是(唱歌)不是歌词。"));
    }
}
