package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonArray;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import java.util.ArrayList;
import java.util.List;

/** Compatibility view into the unified LLM catalog; never persists connection settings. */
public final class AvailableVisionSites {
    private AvailableVisionSites() { }
    public static void ensureLoaded() { }
    public static List<VisionSite> all() { return UnifiedModelCatalog.views(); }
    public static VisionSite get(String id) {
        return all().stream().filter(site -> site.id().equals(id)).findFirst().orElse(null);
    }
    public static VisionSite selected() {
        return chooseSelected(all(), TouhouAIFunConfig.VISION_SELECTED_SITE.get());
    }
    public static String effectiveSelectedId() {
        return TouhouAIFunConfig.VISION_SELECTED_SITE.get();
    }
    static VisionSite chooseSelected(Iterable<VisionSite> sites, String selectedId) {
        if (sites == null || selectedId == null || selectedId.isBlank()) return null;
        for (VisionSite site : sites) {
            if (site.id().equals(selectedId) && site.enabled() && site.hasValidHttpEndpoint()
                    && !site.model().isBlank() && (site.source() == null
                        ? site.apiKeyPresent() : UnifiedModelCatalog.usable(ModelRef.decode(site.id())) && site.imageSupported())) return site;
        }
        return null;
    }
    public static String serializeForClient() {
        JsonArray array = new JsonArray();
        all().forEach(site -> array.add(site.toJson(false)));
        return array.toString();
    }

    static List<VisionSite> defaultSites() {
        List<VisionSite> sites = new ArrayList<>();
        sites.add(new VisionSite("tencent_tokenhub", "腾讯 TokenHub", "tencent",
                "https://tokenhub.tencentmaas.com/v1/chat/completions", "youtu-vita", "", false));
        sites.add(new VisionSite("sensenova", "商汤 SenseNova", "sensenova",
                "https://token.sensenova.cn/v1/chat/completions", "sensenova-6.7-flash-lite", "", false));
        sites.add(new VisionSite("stepfun", "阶跃星辰", "stepfun",
                "https://api.stepfun.com/v1/chat/completions", "step-3.7-flash", "", false));
        sites.add(new VisionSite("stepfun_plan", "阶跃星辰 Step Plan", "stepfun_plan",
                "https://api.stepfun.com/step_plan/v1/chat/completions", "step-3.7-flash", "", false));
        sites.add(new VisionSite("zhipu", "智谱", "zhipu",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6v-flash", "", false));
        sites.add(new VisionSite("qwen", "通义千问", "qwen",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-vl-flash", "", false));
        return sites;
    }
}
