package com.wjx.touhou_aifun.vision;

import java.net.URI;
import java.util.Locale;

/** Conservative endpoint-aware rules; unknown gateways need an explicit capability override. */
public final class ModelImageCapabilities {
    private ModelImageCapabilities() { }

    /** Use only an explicit provider input-modality list; a missing list remains unknown. */
    public static Boolean providerImages(com.google.gson.JsonObject model) {
        com.google.gson.JsonElement inputs = model.get("input_modalities");
        if (inputs == null) inputs = model.get("supported_input_modalities");
        if (inputs == null && model.has("modalities") && model.get("modalities").isJsonObject()) {
            inputs = model.getAsJsonObject("modalities").get("input");
        }
        if (inputs == null || !inputs.isJsonArray()) return null;
        for (var input : inputs.getAsJsonArray()) {
            if (input.isJsonPrimitive() && "image".equalsIgnoreCase(input.getAsString())) return true;
        }
        return false;
    }

    public static boolean supports(ModelMetadataStore.Metadata metadata, String apiType, String endpoint, String model) {
        if (metadata.capability() != VisionCapabilityMode.AUTO) return metadata.capability() == VisionCapabilityMode.SUPPORTED;
        if (metadata.providerSupportsImages() != null) return metadata.providerSupportsImages();
        return knownSupported(apiType, endpoint, model);
    }

    public static boolean knownSupported(String apiType, String endpoint, String model) {
        String name = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        String host;
        String path;
        try { var uri = URI.create(endpoint); host = uri.getHost(); path = uri.getPath().replaceAll("/+$", ""); }
        catch (RuntimeException ignored) { return false; }
        if (host == null) return false;
        boolean anthropic = "anthropic".equals(apiType);
        if (anthropic) {
            if (!path.isBlank() && !path.endsWith("/v1/messages")) return false;
        } else if (!path.endsWith("/chat/completions") && !path.endsWith("/responses")) return false;
        host = host.toLowerCase(Locale.ROOT);
        // https://static.stepfun.com/blog/step-3.7-flash/
        if (host.equals("api.stepfun.com")) return name.equals("step-3.7-flash");
        // https://mimo.mi.com/docs/en-US/tokenplan/integration/opencode
        // V2.5-Pro is text-only: https://mimo.mi.com/models/en-US/mimo-v2.5-pro
        if (host.endsWith(".xiaomimimo.com")) return name.equals("mimo-v2.5") || name.equals("mimo-v2-omni")
                || name.equals("mimo-v2.6-pro") || name.equals("mimo-v2.6-flash") || name.equals("mimo-v2.6-pro-ultraspeed");
        // https://help.aliyun.com/zh/model-studio/vision
        if (host.equals("dashscope.aliyuncs.com") || host.equals("dashscope-intl.aliyuncs.com")) {
            return name.matches("qwen(?:2(?:\\.5)?-vl|3-vl|3\\.[5678])(?:-.*)?");
        }
        // https://docs.bigmodel.cn/ (GLM-V model catalog)
        if (host.equals("open.bigmodel.cn") || host.equals("api.z.ai")) {
            return name.matches("glm-4(?:\\.[156])?v(?:-.*)?");
        }
        // https://platform.claude.com/docs/en/build-with-claude/vision
        if (host.equals("api.anthropic.com") && anthropic) return name.startsWith("claude-3") || name.matches("claude-(?:sonnet|opus|haiku)-4.*");
        // https://developers.openai.com/api/docs/guides/images-vision
        if (host.equals("api.openai.com") && !anthropic) {
            return name.matches("gpt-(?:4o|4\\.1|5(?:\\.[0-9]+)?)(?:-.*)?")
                    || name.matches("o[34](?:-.*)?")
                    || ("chatgpt_subscription".equals(apiType) && name.matches("gpt-6(?:\\.[0-9]+)?-(?:sol|astra|luna)"));
        }
        // Aggregator model names alone do not establish the endpoint's image support.
        return false;
    }
}
