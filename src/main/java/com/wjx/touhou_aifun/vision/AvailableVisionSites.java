package com.wjx.touhou_aifun.vision;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-authoritative visual-site registry persisted separately from LLM/STT/TTS sites. */
public final class AvailableVisionSites {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("touhou_little_maid/sites/vision.json");
    private static final Map<String, VisionSite> SITES = new LinkedHashMap<>();
    private static boolean loaded;

    private AvailableVisionSites() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                JsonElement parsed = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
                JsonArray array = parsed.isJsonArray() ? parsed.getAsJsonArray()
                        : parsed.getAsJsonObject().getAsJsonArray("sites");
                if (array != null) {
                    for (JsonElement element : array) {
                        if (element.isJsonObject()) {
                            VisionSite site = VisionSite.fromJson(element.getAsJsonObject());
                            if (!site.id().isBlank()) SITES.put(site.id(), site);
                        }
                    }
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Unable to read visual site config {}, using defaults", FILE, exception);
        }
        boolean changed = false;
        for (VisionSite site : defaults()) {
            if (!SITES.containsKey(site.id())) {
                SITES.put(site.id(), site);
                changed = true;
            }
        }
        if (changed || SITES.isEmpty()) save();
    }

    public static synchronized List<VisionSite> all() {
        ensureLoaded();
        return List.copyOf(SITES.values());
    }

    public static synchronized VisionSite get(String id) {
        ensureLoaded();
        return SITES.get(id);
    }

    public static synchronized VisionSite selected() {
        ensureLoaded();
        String selected = com.wjx.touhou_aifun.config.TouhouAIFunConfig.VISION_SELECTED_SITE.get();
        VisionSite preferred = SITES.get(selected);
        if (preferred != null && usable(preferred)) return preferred;
        return SITES.values().stream().filter(AvailableVisionSites::usable)
                .findFirst().orElse(null);
    }

    private static boolean usable(VisionSite site) {
        return site != null && site.enabled() && !site.apiKey().isBlank()
                && !site.endpoint().isBlank() && !site.model().isBlank();
    }

    public static synchronized void upsert(VisionSite site) {
        ensureLoaded();
        if (site != null && !site.id().isBlank()) {
            SITES.put(site.id(), site);
            save();
        }
    }

    /** Preserve an existing secret when a client sends a masked site edit. */
    public static synchronized void upsertPreservingSecret(VisionSite incoming) {
        ensureLoaded();
        if (incoming == null || incoming.id().isBlank()) return;
        VisionSite existing = SITES.get(incoming.id());
        if (existing != null && incoming.apiKey().isBlank() && !existing.apiKey().isBlank()) {
            incoming.setApiKey(existing.apiKey());
        }
        upsert(incoming);
    }

    public static synchronized void remove(String id) {
        ensureLoaded();
        if (id != null) SITES.remove(id);
        save();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonArray array = new JsonArray();
            SITES.values().forEach(site -> array.add(site.toJson()));
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(array), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Unable to save visual site config {}", FILE, exception);
        }
    }

    public static synchronized void replaceFrom(List<VisionSite> sites) {
        ensureLoaded();
        Map<String, String> secrets = new LinkedHashMap<>();
        SITES.values().forEach(site -> {
            if (!site.apiKey().isBlank()) secrets.put(site.id(), site.apiKey());
        });
        SITES.clear();
        if (sites != null) {
            sites.stream().filter(site -> site != null && !site.id().isBlank())
                    .forEach(site -> {
                        if (site.apiKey().isBlank()) {
                            String secret = secrets.get(site.id());
                            if (secret != null) site.setApiKey(secret);
                        }
                        SITES.put(site.id(), site);
                    });
        }
        save();
    }

    public static synchronized String serialize() {
        ensureLoaded();
        JsonArray array = new JsonArray();
        SITES.values().forEach(site -> array.add(site.toJson()));
        return array.toString();
    }

    /** Client settings never receive provider API keys over the network. */
    public static synchronized String serializeForClient() {
        ensureLoaded();
        JsonArray array = new JsonArray();
        SITES.values().forEach(site -> array.add(site.toJson(false)));
        return array.toString();
    }

    public static synchronized void replaceFromJson(String payload) {
        try {
            JsonElement parsed = JsonParser.parseString(payload == null ? "[]" : payload);
            JsonArray array = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
            List<VisionSite> sites = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) sites.add(VisionSite.fromJson(element.getAsJsonObject()));
            }
            replaceFrom(sites);
        } catch (Exception exception) {
            LOGGER.warn("Unable to apply visual site sync", exception);
        }
    }

    private static List<VisionSite> defaults() {
        List<VisionSite> sites = new ArrayList<>();
        sites.add(new VisionSite("tencent_tokenhub", "腾讯 TokenHub", "tencent",
                "https://tokenhub.tencentmaas.com/v1/chat/completions", "youtu-vita", false, "", false));
        sites.add(new VisionSite("sensenova", "商汤 SenseNova", "sensenova",
                "https://api.sensenova.cn/v1/llm/chat-completions", "", false, "", false));
        sites.add(new VisionSite("stepfun", "阶跃星辰", "stepfun",
                "https://api.stepfun.com/v1/chat/completions", "step-3.7-flash", false, "", false));
        sites.add(new VisionSite("stepfun_plan", "阶跃星辰 Step Plan", "stepfun_plan",
                "https://api.stepfun.com/step_plan/v1/chat/completions", "step-3.7-flash", false, "", false));
        sites.add(new VisionSite("zhipu", "智谱", "zhipu",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6v-flash", false, "", false));
        sites.add(new VisionSite("qwen", "通义千问", "qwen",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-vl-flash", false, "", false));
        return sites;
    }
}
