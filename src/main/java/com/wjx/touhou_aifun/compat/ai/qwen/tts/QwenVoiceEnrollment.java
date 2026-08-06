package com.wjx.touhou_aifun.compat.ai.qwen.tts;

import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.compat.ai.qwen.QwenShared;
import com.wjx.touhou_aifun.compat.ai.tts.CustomVoiceHttpUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/**
 * One-time voice enrollment for Qwen-TTS clone and design, run when a site is saved.
 *
 * <p>Both flows POST to {@code /services/audio/tts/customization} with {@code action=create} and
 * return {@code output.voice} — the id that is then passed as {@code input.voice} to the normal
 * synthesis endpoint. The enrollment {@code target_model} must match the synth model exactly, so
 * the synth-side model constants in {@link QwenShared} are reused here.
 */
public final class QwenVoiceEnrollment {
    private QwenVoiceEnrollment() {
    }

    /**
     * Registers a cloned voice from a local reference audio sample.
     *
     * @param synthUrl the configured synthesis URL (host is reused to derive the customization endpoint)
     * @return the {@code output.voice} id to use at synthesis time
     */
    public static String createClone(String synthUrl, String secretKey, Map<String, String> headers,
                                     String audioFilePath) throws Exception {
        Path path = CustomVoiceHttpUtil.requireAudioFile(audioFilePath, "Qwen 声音复刻参考音频", ".mp3", ".wav");

        JsonObject audio = new JsonObject();
        audio.addProperty("data", toDataUri(path));

        JsonObject input = new JsonObject();
        input.addProperty("action", "create");
        input.addProperty("target_model", QwenShared.TTS_VC_MODEL);
        input.addProperty("preferred_name", CustomVoiceHttpUtil.newManagedVoiceId("tlm_qwen_vc"));
        input.add("audio", audio);

        JsonObject body = new JsonObject();
        body.addProperty("model", QwenShared.CLONE_ENROLLMENT_MODEL);
        body.add("input", input);

        return enroll(synthUrl, secretKey, headers, body, "创建 Qwen 声音复刻音色");
    }

    /**
     * Registers a designed voice from a free-text voice description prompt.
     *
     * @return the {@code output.voice} id to use at synthesis time
     */
    public static String createDesign(String synthUrl, String secretKey, Map<String, String> headers,
                                      String voicePrompt) throws Exception {
        JsonObject input = new JsonObject();
        input.addProperty("action", "create");
        input.addProperty("target_model", QwenShared.TTS_VD_MODEL);
        input.addProperty("preferred_name", CustomVoiceHttpUtil.newManagedVoiceId("tlm_qwen_vd"));
        input.addProperty("voice_prompt", voicePrompt);
        input.addProperty("preview_text", "你好，这是音色预览。");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("sample_rate", 24000);
        parameters.addProperty("response_format", "wav");

        JsonObject body = new JsonObject();
        body.addProperty("model", QwenShared.DESIGN_ENROLLMENT_MODEL);
        body.add("input", input);
        body.add("parameters", parameters);

        return enroll(synthUrl, secretKey, headers, body, "创建 Qwen 声音设计音色");
    }

    private static String enroll(String synthUrl, String secretKey, Map<String, String> headers,
                                 JsonObject body, String action) throws Exception {
        URI uri = CustomVoiceHttpUtil.replacePath(synthUrl, QwenShared.CUSTOMIZATION_PATH);
        HttpResponse<String> response = CustomVoiceHttpUtil.postJson(uri, secretKey, headers, body);
        JsonObject json = CustomVoiceHttpUtil.requireSuccessJson(response, action);
        return CustomVoiceHttpUtil.requireNestedString(json, action, "output", "voice");
    }

    private static String toDataUri(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        String mime = name.endsWith(".wav") ? "audio/wav" : "audio/mpeg";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }
}
