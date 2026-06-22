package com.wjx.touhou_aifun.compat.ai.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.wjx.touhou_aifun.config.TouhouStepFunConfig;

import java.net.http.HttpRequest;
import java.util.List;

public final class TTSProgressiveSynthesis {
    private static final int MAX_CHUNK_CODE_POINTS = 1_000;

    private TTSProgressiveSynthesis() {
    }

    public static void play(TTSClient client, String message, TTSConfig config, TTSCallback callback) {
        if (!TouhouStepFunConfig.TTS_SENTENCE_STREAMING.get()) {
            client.play(message, config, callback);
            return;
        }

        List<String> chunks = SentenceTextSplitter.split(message, MAX_CHUNK_CODE_POINTS);
        if (chunks.size() <= 1) {
            client.play(message, config, callback);
            return;
        }
        playNext(client, chunks, 0, config, callback);
    }

    private static void playNext(TTSClient client, List<String> chunks, int index,
                                 TTSConfig config, TTSCallback callback) {
        if (index >= chunks.size()) {
            return;
        }
        TTSCallback chunkCallback = new TTSCallback(callback.getMaid(), "", 0) {
            @Override
            public void onSuccess(byte[] data) {
                callback.onSuccess(data);
                playNext(client, chunks, index + 1, config, callback);
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
                callback.onFailure(request, throwable, errorCode);
            }
        };
        client.play(chunks.get(index), config, chunkCallback);
    }
}
