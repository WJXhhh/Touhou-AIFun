package com.wjx.touhou_aifun.client.voice;

import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsSTTConfigScreen;
import com.github.tartaricacid.touhoulittlemaid.client.input.STTChatKey;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.chat.freechat.FreeChatDecision;
import com.wjx.touhou_aifun.client.sound.AIFunStreamSoundManager;
import com.wjx.touhou_aifun.client.sound.QueuedTTSPlaybackManager;
import com.wjx.touhou_aifun.client.sound.SpeechMute;
import com.wjx.touhou_aifun.compat.ai.stt.BufferedSTTClient;
import com.wjx.touhou_aifun.compat.ai.stt.SttSiteSelection;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunFreeChatUtteranceMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Objects;

/**
 * Client-side free-chat coordinator. It owns the volume gate, the one-shot ASR call and the
 * transcript/decision feedback. It never runs the decision service locally: keys stay on the
 * server, and every non-blank transcript is judged there.
 */
@OnlyIn(Dist.CLIENT)
public final class FreeChatController {
    private static final int MAX_IN_FLIGHT_ASR = 2;
    private static final long NOTICE_THROTTLE_MILLIS = 1500L;
    private static final long MIC_RETRY_MILLIS = 5000L;
    private static final int NEARBY_SCAN_TICKS = 10;

    private static final VadCapture CAPTURE = new VadCapture();
    private static final FreeChatSpeechHistory HISTORY = new FreeChatSpeechHistory();

    private static volatile boolean monitorActive;
    private static volatile String monitorDevice = "";
    private static volatile boolean pushToTalkSuspended;
    private static volatile boolean pushToTalkReleaseSeen;
    private static volatile int inFlightAsr;
    private static volatile long sequence = System.nanoTime();
    private static volatile long lastNoticeAt;
    private static volatile long lastMicFailureAt;
    private static volatile long lastMonitorAttemptAt;
    private static volatile long monitorStartedAt;
    private static volatile boolean nearbyMaid;
    private static volatile boolean immediateBargeInSent;

    private static VadSegmenter segmenter;
    private static int segmenterSignature;
    private static String captureDevice = "";
    private static boolean captureMonitor;
    private static int nearbyScanTicks;

    private FreeChatController() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            monitorActive = false;
            CAPTURE.stop();
            captureDevice = "";
            captureMonitor = false;
            HISTORY.clear();
            return;
        }
        if (monitorActive && System.currentTimeMillis() - monitorStartedAt > 500L
                && !(minecraft.screen instanceof AIChatSettingsSTTConfigScreen)) {
            stopMonitor();
        }
        if (monitorActive) {
            ensureMonitor(minecraft);
            return;
        }
        maybeImmediateBargeIn(minecraft);
        if (pushToTalkSuspended
                && !STTChatKey.STT_CHAT_KEY.isDown() && pushToTalkReleaseSeen) {
            pushToTalkSuspended = false;
        }
        pushToTalkReleaseSeen = !STTChatKey.STT_CHAT_KEY.isDown();
        if (!chatConditions(minecraft)) {
            stopCapture();
            return;
        }
        ensureChatCapture(minecraft);
    }

    /** Called by the STT configuration screen; opens the same microphone line in level-only mode. */
    public static void startMonitor(String deviceName) {
        monitorActive = true;
        monitorStartedAt = System.currentTimeMillis();
        monitorDevice = deviceName == null ? "" : deviceName;
        lastMonitorAttemptAt = 0L;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            ensureMonitor(minecraft);
        }
    }

    /** Called when the STT configuration screen is removed. */
    public static void stopMonitor() {
        monitorActive = false;
        monitorDevice = "";
        captureDevice = "";
        captureMonitor = false;
        CAPTURE.stop();
    }

    public static boolean monitorActive() {
        return monitorActive;
    }

    public static int latestMicLevel() {
        return CAPTURE.latestLevel();
    }

    public static String micStatus() {
        return CAPTURE.status();
    }

    public static boolean isTtsActive() {
        try {
            return QueuedTTSPlaybackManager.isPlaying() || AIFunStreamSoundManager.isSpeaking();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static boolean isFreeChatEnabled() {
        try {
            return TouhouAIFunConfig.SPEC.isLoaded() && TouhouAIFunConfig.FREE_CHAT.get();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static boolean hasSupportedMicrophone() {
        return VadCapture.hasSupportedMicrophone();
    }

    private static void maybeImmediateBargeIn(Minecraft minecraft) {
        if (!CAPTURE.isSpeechActive()) {
            immediateBargeInSent = false;
            return;
        }
        if (!isTtsActive()) {
            immediateBargeInSent = false;
            return;
        }
        if (immediateBargeInSent || !isFreeChatEnabled()
                || !"immediate".equals(TouhouAIFunConfig.freeChatBargeIn())) {
            return;
        }
        immediateBargeInSent = true;
        QueuedTTSPlaybackManager.interrupt();
        AIFunStreamSoundManager.interrupt();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        try {
            double range = Math.max(1.0D, Math.min(256.0D, AIConfig.MAID_CAN_CHAT_DISTANCE.get()));
            for (EntityMaid maid : minecraft.level.getEntitiesOfClass(EntityMaid.class,
                    minecraft.player.getBoundingBox().inflate(range),
                    maid -> maid.isAlive() && maid.isOwnedBy(minecraft.player))) {
                AIFunNetwork.sendMaidSpeechStop(maid.getId());
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void suspendForPushToTalk() {
        pushToTalkSuspended = true;
        pushToTalkReleaseSeen = false;
        immediateBargeInSent = false;
        if (!monitorActive) {
            stopCapture();
        }
    }

    public static void resumeAfterPushToTalk() {
        pushToTalkSuspended = false;
        pushToTalkReleaseSeen = true;
    }

    public static void onFeedback(String status, int maidId) {
        if (status == null || status.isBlank() || !isFreeChatEnabled()) {
            return;
        }
        if ("superseded".equals(status)) {
            return;
        }
        if ("self_echo".equals(status)) {
            HISTORY.removeLast();
            notice("status.self_echo", false);
            return;
        }
        if ("accepted".equals(status)) {
            Minecraft minecraft = Minecraft.getInstance();
            String name = "maid";
            if (minecraft != null && minecraft.level != null
                    && minecraft.level.getEntity(maidId) instanceof EntityMaid maid) {
                name = maid.getName().getString();
            }
            notice("status.accepted", true, name);
            return;
        }
        notice("status." + status, false);
    }

    private static boolean chatConditions(Minecraft minecraft) {
        try {
            if (!TouhouAIFunConfig.SPEC.isLoaded() || !TouhouAIFunConfig.FREE_CHAT.get()) {
                return false;
            }
            LocalPlayer player = minecraft.player;
            if (player == null || minecraft.level == null || player.isSpectator()
                    || player.isDeadOrDying() || player.isSleeping()) {
                return false;
            }
            if (minecraft.screen != null || minecraft.getOverlay() != null
                    || !minecraft.isWindowActive() || minecraft.isPaused()) {
                return false;
            }
            if (!minecraft.mouseHandler.isMouseGrabbed()) {
                return false;
            }
            if (!AIConfig.STT_ENABLED.get()) {
                return false;
            }
            if (SpeechMute.muted() || pushToTalkSuspended || STTChatKey.STT_CHAT_KEY.isDown()) {
                return false;
            }
            if (SttSiteSelection.resolveBuffered() == null) {
                return false;
            }
            if (nearbyScanTicks > 0) {
                nearbyScanTicks--;
                return nearbyMaid;
            }
            nearbyScanTicks = NEARBY_SCAN_TICKS;
            double range = Math.max(1.0D, Math.min(256.0D, AIConfig.MAID_CAN_CHAT_DISTANCE.get()));
            nearbyMaid = !minecraft.level.getEntitiesOfClass(EntityMaid.class,
                    player.getBoundingBox().inflate(range),
                    maid -> maid.isAlive() && maid.isOwnedBy(player)).isEmpty();
            return nearbyMaid;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void ensureChatCapture(Minecraft minecraft) {
        try {
            if (System.currentTimeMillis() - lastMicFailureAt < MIC_RETRY_MILLIS) {
                return;
            }
            int threshold = TouhouAIFunConfig.FREE_CHAT_THRESHOLD.get();
            CAPTURE.setActiveThreshold(threshold, isTtsActive());
            int signature = Objects.hash(
                    TouhouAIFunConfig.FREE_CHAT_SILENCE_MILLIS.get(),
                    TouhouAIFunConfig.FREE_CHAT_MIN_SPEECH_MILLIS.get(),
                    TouhouAIFunConfig.FREE_CHAT_MAX_UTTERANCE_MILLIS.get(),
                    TouhouAIFunConfig.FREE_CHAT_PREROLL_MILLIS.get());
            if (segmenter == null || segmenterSignature != signature) {
                segmenter = new VadSegmenter(VadSegmenter.Config.fromMillis(16000,
                        TouhouAIFunConfig.FREE_CHAT_SILENCE_MILLIS.get(),
                        TouhouAIFunConfig.FREE_CHAT_MIN_SPEECH_MILLIS.get(),
                        TouhouAIFunConfig.FREE_CHAT_MAX_UTTERANCE_MILLIS.get(),
                        TouhouAIFunConfig.FREE_CHAT_PREROLL_MILLIS.get()));
                segmenterSignature = signature;
            }
            String device = "";
            try {
                String configured = AIConfig.STT_MICROPHONE.get();
                device = configured == null ? "" : configured;
            } catch (RuntimeException ignored) {
            }
            boolean needsStart = !CAPTURE.isRunning() || captureMonitor
                    || !Objects.equals(captureDevice, device);
            if (!needsStart) {
                return;
            }
            CAPTURE.setMonitorMode(false);
            CAPTURE.start(device, segmenter, chatListener());
            captureDevice = device;
            captureMonitor = false;
        } catch (RuntimeException unavailable) {
            lastMicFailureAt = System.currentTimeMillis();
        }
    }

    private static void ensureMonitor(Minecraft minecraft) {
        try {
            if (SpeechMute.muted() || STTChatKey.STT_CHAT_KEY.isDown()) {
                // The push-to-talk key owns the microphone; do not open a second line.
                return;
            }
            if (System.currentTimeMillis() - lastMonitorAttemptAt < MIC_RETRY_MILLIS) {
                return;
            }
            boolean needsStart = !CAPTURE.isRunning() || !captureMonitor
                    || !Objects.equals(captureDevice, monitorDevice);
            if (!needsStart) {
                return;
            }
            lastMonitorAttemptAt = System.currentTimeMillis();
            CAPTURE.setMonitorMode(true);
            CAPTURE.start(monitorDevice, null, monitorListener());
            captureDevice = monitorDevice;
            captureMonitor = true;
        } catch (RuntimeException unavailable) {
            lastMonitorAttemptAt = System.currentTimeMillis();
        }
    }

    private static void stopCapture() {
        if (CAPTURE.isRunning()) {
            CAPTURE.stop();
        }
        captureDevice = "";
        captureMonitor = false;
    }

    private static VadCapture.Listener chatListener() {
        return new VadCapture.Listener() {
            @Override
            public void onSegment(byte[] wav, int speechMillis, int peak) {
                handleSegment(wav, speechMillis, peak);
            }

            @Override
            public void onFailure(String reason) {
                lastMicFailureAt = System.currentTimeMillis();
                if (!monitorActive) {
                    if ("mic_busy".equals(reason)) {
                        notice("status.mic_busy", false);
                    } else if ("mic_error".equals(reason)) {
                        notice("status.mic_error", false);
                    } else {
                        notice("status.mic_missing", false);
                    }
                }
            }
        };
    }

    private static VadCapture.Listener monitorListener() {
        return new VadCapture.Listener() {
            @Override
            public void onSegment(byte[] wav, int speechMillis, int peak) {
            }

            @Override
            public void onFailure(String reason) {
                lastMonitorAttemptAt = System.currentTimeMillis();
            }
        };
    }

    private static void handleSegment(byte[] wav, int speechMillis, int peak) {
        Minecraft minecraft = Minecraft.getInstance();
        if (monitorActive || !isFreeChatEnabled() || minecraft.level == null
                || minecraft.player == null) {
            return;
        }
        if (inFlightAsr >= MAX_IN_FLIGHT_ASR) {
            notice("status.busy", false);
            return;
        }
        var site = SttSiteSelection.resolveBuffered();
        if (site == null) {
            notice("status.stt_unsupported", false);
            return;
        }
        PlayerViewScene.Capture scene = PlayerViewScene.capture(minecraft);
        if (scene == null) {
            notice("status.no_maid", false);
            return;
        }
        BufferedSTTClient client;
        try {
            if (!(site.client() instanceof BufferedSTTClient buffered)) {
                notice("status.stt_unsupported", false);
                return;
            }
            client = buffered;
        } catch (RuntimeException unavailable) {
            notice("status.stt_failed", false);
            return;
        }
        inFlightAsr++;
        try {
            client.transcribe(wav, new ResponseCallback<>() {
                @Override
                public void onFailure(@Nullable HttpRequest request, Throwable throwable, int errorCode) {
                    minecraft.execute(() -> {
                        inFlightAsr = Math.max(0, inFlightAsr - 1);
                        notice("status.stt_failed", false);
                    });
                }

                @Override
                public void onSuccess(String chatText) {
                    minecraft.execute(() -> {
                        inFlightAsr = Math.max(0, inFlightAsr - 1);
                        handleTranscript(chatText, speechMillis, scene);
                    });
                }
            });
        } catch (RuntimeException unavailable) {
            inFlightAsr = Math.max(0, inFlightAsr - 1);
            notice("status.stt_failed", false);
        }
    }

    private static void handleTranscript(String chatText, int speechMillis, PlayerViewScene.Capture scene) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isFreeChatEnabled() || minecraft.player == null || minecraft.level == null) {
            return;
        }
        String transcript = FreeChatDecision.sanitize(chatText, 2000);
        if (transcript.isBlank()) {
            notice("status.empty", false);
            return;
        }
        List<String> recent = HISTORY.recent();
        HISTORY.add(transcript);
        String language;
        try {
            language = minecraft.getLanguageManager().getSelected();
        } catch (RuntimeException unavailable) {
            language = "en_us";
        }
        try {
            AIFunNetwork.sendFreeChatUtterance(new AIFunFreeChatUtteranceMessage(
                    sequence++, scene.candidateMaidIds(), transcript, scene.summary(), recent,
                    language, Math.max(0, speechMillis)));
        } catch (RuntimeException unavailable) {
            return;
        }
        String preview = FreeChatDecision.sanitize(transcript, 48);
        notice("heard", false, preview);
    }

    private static void notice(String key, boolean force, Object... args) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastNoticeAt < NOTICE_THROTTLE_MILLIS) {
            return;
        }
        lastNoticeAt = now;
        minecraft.player.displayClientMessage(
                Component.translatable("gui.touhou_aifun.free_chat." + key, args), true);
    }
}
