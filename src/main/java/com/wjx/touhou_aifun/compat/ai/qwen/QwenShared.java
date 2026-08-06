package com.wjx.touhou_aifun.compat.ai.qwen;

import org.apache.commons.lang3.StringUtils;

/**
 * Shared constants for the Alibaba Cloud / Bailian (DashScope) "qwen" provider.
 *
 * <p>All three capabilities point at the same DashScope account on the China site
 * ({@code dashscope.aliyuncs.com}). The unification strategy is deliberate:
 * <ul>
 *   <li>LLM and STT both ride the OpenAI-compatible endpoint, so switching between
 *       qwen text/ASR models never changes the request shape.</li>
 *   <li>TTS has no OpenAI-compatible endpoint on DashScope, so it uses the native
 *       multimodal-generation HTTP endpoint. Staying inside the {@code qwen3-tts-*}
 *       family keeps that request shape stable too.</li>
 * </ul>
 * Avoid the legacy WebSocket-only speech models (cosyvoice, paraformer realtime) and
 * the async file-transcription models (fun-asr, *-filetrans) — those use entirely
 * different protocols and are the source of the "switch model, break everything" pain.
 */
public final class QwenShared {
    /**
     * Deliberately reuses the base mod's Alibaba Cloud identity ("aliyun"): this provider "takes
     * over" that api_type so it inherits the 阿里云 icon ({@code defaultIcon("aliyun")}) and display
     * name. The STT registration overrides the base mod's low-quality NLS-gateway recognizer, and
     * TTS/LLM are added under the same identity. See LittleMaidCompat for the registration order.
     */
    public static final String API_TYPE = "aliyun";

    /** OpenAI-compatible endpoint — used by both LLM and qwen3-asr-flash STT. */
    public static final String COMPATIBLE_CHAT_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /** DashScope native multimodal-generation endpoint — used by qwen3-tts synthesis (all voice types). */
    public static final String TTS_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    /** Path of the voice customization endpoint (clone/design enrollment), derived from the synth host. */
    public static final String CUSTOMIZATION_PATH = "/api/v1/services/audio/tts/customization";

    /** Preset-voice synthesis model. */
    public static final String TTS_FLASH_MODEL = "qwen3-tts-flash";

    /**
     * Voice-clone synthesis model. The enrollment {@code target_model} MUST match the synth model
     * exactly, so both the form layout and the client share this constant. Pinned by date because
     * DashScope versions these models; bump together with {@link #CLONE_ENROLLMENT_MODEL} when needed.
     */
    public static final String TTS_VC_MODEL = "qwen3-tts-vc-2026-01-22";
    public static final String CLONE_ENROLLMENT_MODEL = "qwen-voice-enrollment";

    /** Voice-design synthesis model + its enrollment model. Same matching constraint as clone. */
    public static final String TTS_VD_MODEL = "qwen3-tts-vd-2026-01-26";
    public static final String DESIGN_ENROLLMENT_MODEL = "qwen-voice-design";

    private QwenShared() {
    }

    /**
     * Trims whitespace from the API key and validates it is not empty.
     *
     * @return the trimmed key, or {@code null} if the key is blank after trimming
     */
    public static String resolveSecretKey(String secretKey) {
        return StringUtils.trimToNull(secretKey);
    }
}
