package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Ephemeral, secret-free view of the connected server's sites. Never touches vision.json. */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class ClientVisionSitesSnapshot {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile List<VisionSite> sites = List.of();

    private ClientVisionSitesSnapshot() {
    }

    public static List<VisionSite> all() {
        return sites;
    }

    public static void replaceFromJson(String payload) {
        try {
            JsonElement parsed = JsonParser.parseString(payload == null ? "[]" : payload);
            JsonArray array = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
            List<VisionSite> replacement = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    VisionSite site = VisionSite.fromJson(element.getAsJsonObject());
                    if (!site.id().isBlank()) replacement.add(site);
                }
            }
            sites = List.copyOf(replacement);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to apply secret-free visual site snapshot", exception);
        }
    }

    public static void clear() {
        sites = List.of();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
