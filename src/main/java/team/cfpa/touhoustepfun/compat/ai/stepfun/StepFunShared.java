package team.cfpa.touhoustepfun.compat.ai.stepfun;

import net.minecraft.resources.ResourceLocation;

import java.net.URI;

public final class StepFunShared {
    public static final String API_TYPE = "stepfun";
    public static final ResourceLocation ICON = new ResourceLocation("touhou_aifun", "textures/gui/ai_chat/stepfun.png");

    public static boolean isStepFunApi(String url) {
        try {
            return "api.stepfun.com".equalsIgnoreCase(URI.create(url).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private StepFunShared() {
    }
}
