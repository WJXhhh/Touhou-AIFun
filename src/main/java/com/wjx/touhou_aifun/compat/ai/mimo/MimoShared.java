package com.wjx.touhou_aifun.compat.ai.mimo;

import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;

public final class MimoShared {
    public static final String API_TYPE = "mimo";
    public static final ResourceLocation ICON = new ResourceLocation("touhou_aifun", "textures/gui/ai_chat/mimo.png");

    private MimoShared() {
    }

    /**
     * Trims whitespace from the API key and validates it is not empty.
     *
     * @return the trimmed key, or {@code null} if the key is blank after trimming
     */
    public static String resolveSecretKey(String secretKey) {
        String trimmed = StringUtils.trimToNull(secretKey);
        if (trimmed == null) {
            return null;
        }
        return trimmed;
    }
}
