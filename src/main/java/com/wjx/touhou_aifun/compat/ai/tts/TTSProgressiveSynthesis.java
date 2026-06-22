package com.wjx.touhou_aifun.compat.ai.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;

public final class TTSProgressiveSynthesis {
    private static final int MAX_CHUNK_CODE_POINTS = 1_000;

    private TTSProgressiveSynthesis() {
    }

    public static void play(TTSClient client, String message, TTSConfig config, TTSCallback callback) {
        UUID maidId = callback.getMaid().getUUID();
        // Captured generation; if the player sends a new request and its reply takes over speaking,
        // ChatFlowManager bumps the generation and this run stops.
        int generation = ChatFlowManager.ttsGeneration(maidId);

        if (!TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get()) {
            client.play(message, config, gated(callback, maidId, generation));
            return;
        }

        List<String> chunks = SentenceTextSplitter.split(message, MAX_CHUNK_CODE_POINTS);
        if (chunks.size() <= 1) {
            client.play(message, config, gated(callback, maidId, generation));
            return;
        }
        playNext(client, chunks, 0, config, callback, maidId, generation);
    }

    private static void playNext(TTSClient client, List<String> chunks, int index,
                                 TTSConfig config, TTSCallback callback, UUID maidId, int generation) {
        if (index >= chunks.size() || ChatFlowManager.ttsGeneration(maidId) != generation) {
            return;
        }
        TTSCallback chunkCallback = new TTSCallback(callback.getMaid(), "", 0) {
            @Override
            public void onSuccess(byte[] data) {
                if (ChatFlowManager.ttsGeneration(maidId) != generation) {
                    return;
                }
                callback.onSuccess(data);
                playNext(client, chunks, index + 1, config, callback, maidId, generation);
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
                callback.onFailure(request, throwable, errorCode);
            }
        };
        client.play(chunks.get(index), config, chunkCallback);
    }

    /** Wraps a callback so late-arriving audio is dropped once a newer reply has taken over. */
    private static TTSCallback gated(TTSCallback callback, UUID maidId, int generation) {
        return new TTSCallback(callback.getMaid(), "", 0) {
            @Override
            public void onSuccess(byte[] data) {
                if (ChatFlowManager.ttsGeneration(maidId) != generation) {
                    return;
                }
                callback.onSuccess(data);
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
                callback.onFailure(request, throwable, errorCode);
            }
        };
    }
}
