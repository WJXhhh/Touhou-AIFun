package com.wjx.touhou_aifun.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.network.message.AIFunSettingsMessage;
import com.wjx.touhou_aifun.network.message.AIFunTTSInterruptMessage;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureRequestMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureChunkMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesRequestMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesSyncMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSettingsMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSiteSaveMessage;
import com.wjx.touhou_aifun.vision.AvailableVisionSites;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.VisionCaptureTransport;

import java.util.Optional;

public final class AIFunNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TouhouAIFun.MOD_ID, "network"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private AIFunNetwork() {
    }

    public static void init() {
        CHANNEL.registerMessage(0, AIFunTTSStreamMessage.class,
                AIFunTTSStreamMessage::encode,
                AIFunTTSStreamMessage::decode,
                AIFunTTSStreamMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, AIFunSettingsMessage.class,
                AIFunSettingsMessage::encode,
                AIFunSettingsMessage::decode,
                AIFunSettingsMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, AIFunTTSInterruptMessage.class,
                AIFunTTSInterruptMessage::encode,
                AIFunTTSInterruptMessage::decode,
                AIFunTTSInterruptMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, AIFunVisionCaptureRequestMessage.class,
                AIFunVisionCaptureRequestMessage::encode,
                AIFunVisionCaptureRequestMessage::decode,
                AIFunVisionCaptureRequestMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4, AIFunVisionCaptureChunkMessage.class,
                AIFunVisionCaptureChunkMessage::encode,
                AIFunVisionCaptureChunkMessage::decode,
                AIFunVisionCaptureChunkMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, AIFunVisionSitesRequestMessage.class,
                AIFunVisionSitesRequestMessage::encode, AIFunVisionSitesRequestMessage::decode,
                AIFunVisionSitesRequestMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(6, AIFunVisionSitesSyncMessage.class,
                AIFunVisionSitesSyncMessage::encode, AIFunVisionSitesSyncMessage::decode,
                AIFunVisionSitesSyncMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(7, AIFunVisionSettingsMessage.class,
                AIFunVisionSettingsMessage::encode, AIFunVisionSettingsMessage::decode,
                AIFunVisionSettingsMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, AIFunVisionSiteSaveMessage.class,
                AIFunVisionSiteSaveMessage::encode, AIFunVisionSiteSaveMessage::decode,
                AIFunVisionSiteSaveMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendToPlayer(AIFunTTSStreamMessage message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** Tell clients near the maid to stop the previous reply's TTS so a newer one can take over. */
    public static void sendInterruptTts(EntityMaid maid) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> maid),
                new AIFunTTSInterruptMessage(maid.getId()));
    }

    public static void sendSettingsToServer(boolean sentenceStreaming, boolean llmStreaming,
                                            boolean emotionControl, boolean emotionInText) {
        CHANNEL.sendToServer(new AIFunSettingsMessage(sentenceStreaming, llmStreaming, emotionControl, emotionInText));
    }

    public static void sendVisionCaptureRequest(ServerPlayer player, java.util.UUID requestId, int maidId,
                                                String focus, String scanJson) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AIFunVisionCaptureRequestMessage(requestId, maidId, focus, scanJson));
    }

    public static void sendVisionCaptureChunk(AIFunVisionCaptureChunkMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void acceptVisionCaptureChunk(ServerPlayer sender, AIFunVisionCaptureChunkMessage message) {
        VisionCaptureTransport.acceptChunk(sender, message);
    }

    public static void requestVisionSitesFromServer() {
        CHANNEL.sendToServer(new AIFunVisionSitesRequestMessage());
    }

    public static void sendVisionSettingsToServer(boolean visionEnabled, boolean shallowScanEnabled,
                                                  String selectedSite) {
        CHANNEL.sendToServer(new AIFunVisionSettingsMessage(visionEnabled, shallowScanEnabled, selectedSite));
    }

    public static void sendVisionSiteToServer(AIFunVisionSiteSaveMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendVisionSitesToPlayer(ServerPlayer player) {
        AvailableVisionSites.ensureLoaded();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new AIFunVisionSitesSyncMessage(
                AvailableVisionSites.serializeForClient(), TouhouAIFunConfig.VISION_ENABLED.get(),
                TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get(), TouhouAIFunConfig.VISION_SELECTED_SITE.get(),
                !player.hasPermissions(2)));
    }
}
