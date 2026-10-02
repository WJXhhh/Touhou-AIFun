package com.wjx.touhou_aifun.compat.ai.anthropic;

import net.minecraft.resources.ResourceLocation;
import com.wjx.touhou_aifun.compat.ai.deepseek.DeepSeekModels;

/**
 * Shared constants for the Anthropic-protocol LLM option.
 *
 * <p>The default endpoint is DeepSeek's Anthropic-compatible API
 * ({@code https://api.deepseek.com/anthropic}, {@code POST /v1/messages}, {@code x-api-key}
 * auth). The URL is user-editable, so any other Anthropic Messages compatible endpoint also
 * works. Web search is intentionally not part of this chat site: the addon exposes a normal
 * {@code web_search(query)} tool whose provider layer may use DeepSeek native search separately.
 */
public final class AnthropicShared {
    /** Serializer registration key and {@code api_type} written to {@code llm.json}. */
    public static final String API_TYPE = "anthropic";
    /**
     * Site id used by the default site. MUST equal the registration key: the base mod keys the
     * provider list by the registration key ({@code AvailableSites.addDefaultSites}) while the
     * list buttons operate by {@code site.id()} — a mismatch makes the edit/toggle/delete
     * buttons silently no-op.
     */
    public static final String DEFAULT_SITE_ID = API_TYPE;
    public static final ResourceLocation ICON = new ResourceLocation("touhou_aifun", "textures/gui/ai_chat/anthropic.png");
    /** DeepSeek's Anthropic-compatible endpoint. */
    public static final String DEFAULT_URL = "https://api.deepseek.com/anthropic";
    public static final String DEFAULT_MODEL = DeepSeekModels.FLASH;
    /** Extra model offered by the default site (thinking mode). */
    public static final String DEFAULT_MODEL_PRO = DeepSeekModels.PRO;

    private AnthropicShared() {
    }
}
