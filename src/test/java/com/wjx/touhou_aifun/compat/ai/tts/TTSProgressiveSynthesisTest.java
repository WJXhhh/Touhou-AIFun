package com.wjx.touhou_aifun.compat.ai.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.wjx.touhou_aifun.compat.ai.stepfun.tts.StepFunTTSClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TTSProgressiveSynthesisTest {
    @Test
    void ordinaryStepReplyRetainsTheWholeSongAndItsLeadingCue() {
        TTSClient step = new StepFunTTSClient(null, null);
        String song = "(唱歌)风吹过山岗，星光落在窗。\n明天再出发，把心愿轻轻唱。";
        assertEquals(List.of(song), TTSProgressiveSynthesis.synthesisChunks(step, song, true));
    }

    @Test
    void stepOwnsProviderSizeSplittingEvenForLongReplies() {
        TTSClient step = new StepFunTTSClient(null, null);
        String song = "(唱歌)" + "山川，星光。".repeat(250);
        assertEquals(List.of(song), TTSProgressiveSynthesis.synthesisChunks(step, song, true));
    }

    @Test
    void otherProvidersStillHonorTheProgressiveSetting() {
        TTSClient other = (message, config, callback) -> {};
        String reply = "你好，今天一起出发吧。";
        assertTrue(TTSProgressiveSynthesis.synthesisChunks(other, reply, true).size() > 1);
        assertEquals(List.of(reply), TTSProgressiveSynthesis.synthesisChunks(other, reply, false));
    }
}
