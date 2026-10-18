package com.wjx.touhou_aifun.compat.ai.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;

/**
 * An STT client that can transcribe an already-captured WAV utterance. TLM's regular
 * {@code STTClient.startRecord/stopRecord} owns the microphone for push-to-talk; free chat needs to
 * feed volume-gated segments without opening a second recording session.
 */
public interface BufferedSTTClient {
    void transcribe(byte[] wav, ResponseCallback<String> callback);
}
