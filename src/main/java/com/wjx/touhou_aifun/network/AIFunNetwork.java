package com.wjx.touhou_aifun.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.network.message.AIFunSettingsMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidAccessMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidConfigSaveMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidBatchConfigMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidListRequestMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidListSyncMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidRecallMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidHighlightMessage;
import com.wjx.touhou_aifun.network.message.AIFunMaidOpenBehaviorMessage;
import com.wjx.touhou_aifun.network.message.AIFunTTSInterruptMessage;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureRequestMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureChunkMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureFailureMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureCancelMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesRequestMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesSyncMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSettingsMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSiteSaveMessage;
import com.wjx.touhou_aifun.vision.AvailableVisionSites;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.VisionCaptureTransport;
import com.wjx.touhou_aifun.maid.management.MaidAIConfigSnapshot;
import com.wjx.touhou_aifun.maid.management.MaidManagementService;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;

import java.util.Optional;
import java.util.UUID;

public final class AIFunNetwork {
    // Version 10 adds subscription reasoning summary and effort preferences to GUI actions.
    private static final String VERSION = "11";
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
        CHANNEL.registerMessage(9, AIFunVisionCaptureFailureMessage.class,
                AIFunVisionCaptureFailureMessage::encode, AIFunVisionCaptureFailureMessage::decode,
                AIFunVisionCaptureFailureMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(10, AIFunVisionCaptureCancelMessage.class,
                AIFunVisionCaptureCancelMessage::encode, AIFunVisionCaptureCancelMessage::decode,
                AIFunVisionCaptureCancelMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(11, AIFunMaidAccessMessage.class,
                AIFunMaidAccessMessage::encode, AIFunMaidAccessMessage::decode,
                AIFunMaidAccessMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(12, AIFunMaidListRequestMessage.class,
                AIFunMaidListRequestMessage::encode, AIFunMaidListRequestMessage::decode,
                AIFunMaidListRequestMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(13, AIFunMaidListSyncMessage.class,
                AIFunMaidListSyncMessage::encode, AIFunMaidListSyncMessage::decode,
                AIFunMaidListSyncMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(14, AIFunMaidConfigSaveMessage.class,
                AIFunMaidConfigSaveMessage::encode, AIFunMaidConfigSaveMessage::decode,
                AIFunMaidConfigSaveMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(15, AIFunMaidRecallMessage.class,
                AIFunMaidRecallMessage::encode, AIFunMaidRecallMessage::decode,
                AIFunMaidRecallMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(16, AIFunMaidBatchConfigMessage.class,
                AIFunMaidBatchConfigMessage::encode, AIFunMaidBatchConfigMessage::decode,
                AIFunMaidBatchConfigMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(17, AIFunMaidOpenBehaviorMessage.class,
                AIFunMaidOpenBehaviorMessage::encode, AIFunMaidOpenBehaviorMessage::decode,
                AIFunMaidOpenBehaviorMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(18, AIFunMaidHighlightMessage.class,
                AIFunMaidHighlightMessage::encode, AIFunMaidHighlightMessage::decode,
                AIFunMaidHighlightMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(19, com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage.class,
                com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage::encode,
                com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage::decode,
                com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(20, com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage.class,
                com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage::encode,
                com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage::decode,
                com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /**
     * Sends a TLM audio message to the people allowed to hear this maid. Private maids preserve
     * TLM's owner-only behaviour; public maids are audible to every client currently tracking the
     * maid, just like an ordinary positional entity sound.
     */
    public static void sendMaidTtsAudio(EntityMaid maid, Object message) {
        runForMaid(maid, () -> {
            if (PublicMaidAccess.isPublic(maid)) {
                NetworkHandler.sendToTrackingEntity(message, maid);
            } else if (maid.getOwner() instanceof ServerPlayer owner) {
                NetworkHandler.sendToClientPlayer(message, owner);
            }
        });
    }

    /** Same audience policy as {@link #sendMaidTtsAudio(EntityMaid, Object)} for PCM stream packets. */
    public static void sendMaidTtsStream(EntityMaid maid, AIFunTTSStreamMessage message) {
        runForMaid(maid, () -> {
            if (PublicMaidAccess.isPublic(maid)) {
                CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> maid), message);
            } else if (maid.getOwner() instanceof ServerPlayer owner) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), message);
            }
        });
    }

    /** Tell clients near the maid to stop the previous reply's TTS so a newer one can take over. */
    public static void sendInterruptTts(EntityMaid maid) {
        runForMaid(maid, () -> {
            AIFunTTSInterruptMessage message = new AIFunTTSInterruptMessage(maid.getId());
            if (PublicMaidAccess.isPublic(maid)) {
                CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> maid), message);
            } else if (maid.getOwner() instanceof ServerPlayer owner) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), message);
            }
        });
    }

    private static void runForMaid(EntityMaid maid, Runnable action) {
        if (!(maid.level() instanceof ServerLevel level) || !maid.isAlive()) {
            return;
        }
        if (level.getServer().isSameThread()) {
            action.run();
        } else {
            level.getServer().execute(action);
        }
    }

    public static void sendSettingsToServer(boolean sentenceStreaming, boolean llmStreaming,
                                            boolean emotionControl, boolean emotionInText) {
        CHANNEL.sendToServer(new AIFunSettingsMessage(sentenceStreaming, llmStreaming, emotionControl, emotionInText));
    }

    public static void sendMaidAccessToServer(int maidId, boolean publicMaid, boolean friendlyFireAllowed) {
        CHANNEL.sendToServer(new AIFunMaidAccessMessage(maidId, publicMaid, friendlyFireAllowed));
    }

    public static void requestMaidList(boolean openScreen) {
        CHANNEL.sendToServer(new AIFunMaidListRequestMessage(openScreen));
    }

    public static void sendMaidListToPlayer(ServerPlayer player, String status, boolean openScreen) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AIFunMaidListSyncMessage(MaidManagementService.list(player), status, openScreen));
    }

    public static void saveManagedMaidConfig(UUID maidId, MaidAIConfigSnapshot config,
                                             boolean publicMaid, boolean friendlyFireAllowed, int fieldMask) {
        CHANNEL.sendToServer(new AIFunMaidConfigSaveMessage(maidId, config, publicMaid,
                friendlyFireAllowed, fieldMask));
    }

    public static void recallManagedMaid(UUID maidId) {
        CHANNEL.sendToServer(new AIFunMaidRecallMessage(maidId));
    }

    public static void openManagedMaidBehavior(UUID maidId) {
        CHANNEL.sendToServer(new AIFunMaidOpenBehaviorMessage(maidId));
    }

    public static void highlightManagedMaid(UUID maidId) {
        CHANNEL.sendToServer(new AIFunMaidHighlightMessage(maidId));
    }

    public static void copyManagedMaidConfigToAll(UUID sourceMaidId) {
        CHANNEL.sendToServer(new AIFunMaidBatchConfigMessage(sourceMaidId));
    }

    public static void sendVisionCaptureRequest(ServerPlayer player, java.util.UUID requestId, int maidId,
                                                java.util.UUID maidUuid) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AIFunVisionCaptureRequestMessage(requestId, maidId, maidUuid));
    }

    public static void sendVisionCaptureCancel(ServerPlayer player, java.util.UUID requestId,
                                               java.util.UUID maidUuid) {
        if (player == null || player.connection == null || requestId == null || maidUuid == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AIFunVisionCaptureCancelMessage(requestId, maidUuid));
    }

    public static void sendVisionCaptureChunk(AIFunVisionCaptureChunkMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendVisionCaptureFailure(AIFunVisionCaptureFailureMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void acceptVisionCaptureChunk(ServerPlayer sender, AIFunVisionCaptureChunkMessage message) {
        VisionCaptureTransport.acceptChunk(sender, message);
    }

    public static void acceptVisionCaptureFailure(ServerPlayer sender, AIFunVisionCaptureFailureMessage message) {
        VisionCaptureTransport.acceptFailure(sender, message.requestId(), message.maidId(),
                message.maidUuid(), message.reason());
    }

    public static void requestVisionSitesFromServer() {
        CHANNEL.sendToServer(new AIFunVisionSitesRequestMessage());
    }

    public static void sendChatGPTAction(com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendChatGPTState(ServerPlayer player, com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendVisionSettingsToServer(boolean visionEnabled, boolean shallowScanEnabled,
                                                  String selectedSite) {
        CHANNEL.sendToServer(new AIFunVisionSettingsMessage(visionEnabled, shallowScanEnabled, selectedSite));
    }

    public static void sendVisionSiteToServer(AIFunVisionSiteSaveMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendVisionSitesToPlayer(ServerPlayer player) {
        sendVisionSitesToPlayer(player, "");
    }

    public static void sendVisionSitesToPlayer(ServerPlayer player, String operationStatus) {
        AvailableVisionSites.ensureLoaded();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new AIFunVisionSitesSyncMessage(
                AvailableVisionSites.serializeForClient(), TouhouAIFunConfig.VISION_ENABLED.get(),
                TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get(), AvailableVisionSites.effectiveSelectedId(),
                !player.hasPermissions(2), operationStatus == null ? "" : operationStatus));
    }
}
