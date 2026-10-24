package com.wjx.touhou_aifun.client.voice;

import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsSTTConfigScreen;
import com.github.tartaricacid.touhoulittlemaid.client.input.STTChatKey;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
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
    private static final long NO_FEEDBACK_TIMEOUT_MILLIS = 20000L;
    /** Brief GUI/pause transitions keep the line open instead of re-opening the audio device. */
    private static final long CAPTURE_STOP_GRACE_MILLIS = 2000L;
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
    private static volatile boolean awaitingFeedback;
    private static volatile long awaitingSince;
    private static volatile long captureStopDeadline;
    private static volatile PlayerViewScene.Capture pendingScene;
    private static volatile long pendingSceneAt;

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
        if (awaitingFeedback
                && System.currentTimeMillis() - awaitingSince > NO_FEEDBACK_TIMEOUT_MILLIS) {
            awaitingFeedback = false;
            TouhouAIFun.LOGGER.warn("Free chat: the server sent no verdict within {} ms",
                    NO_FEEDBACK_TIMEOUT_MILLIS);
            notice("status.no_response", true);
        }
        if (minecraft.level == null || minecraft.player == null) {
            monitorActive = false;
            captureStopDeadline = 0L;
            pendingScene = null;
            pendingSceneAt = 0L;
            CAPTURE.stop();
            captureDevice = "";
            captureMonitor = false;
            HISTORY.clear();
            FreeChatNotifier.clear();
            return;
        }
        if (monitorActive && System.currentTimeMillis() - monitorStartedAt > 500L
                && !(minecraft.screen instanceof AIChatSettingsSTTConfigScreen)) {
            stopMonitor();
        }
        if (monitorActive) {
            captureStopDeadline = 0L;
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
            // Keep the microphone open across brief transitions (chat screen, ESC menu, hotbar
            // focus): closing and re-opening a JavaSound line is expensive and can block the
            // render thread for seconds on Windows audio drivers.
            long now = System.currentTimeMillis();
            if (captureStopDeadline == 0L) {
                captureStopDeadline = now + CAPTURE_STOP_GRACE_MILLIS;
            }
            if (now >= captureStopDeadline) {
                captureStopDeadline = 0L;
                stopCapture();
            }
            return;
        }
        captureStopDeadline = 0L;
        ensureChatCapture(minecraft);
    }

    /** Called by the STT configuration screen; opens the same microphone line in level-only mode. */
    public static void startMonitor(String deviceName) {
        monitorActive = true;
        monitorStartedAt = System.currentTimeMillis();
        monitorDevice = deviceName == null ? "" : deviceName;
        lastMonitorAttemptAt = 0L;
        captureStopDeadline = 0L;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            ensureMonitor(minecraft);
        }
    }

    /** Called when the STT configuration screen is removed. */
    public static void stopMonitor() {
        monitorActive = false;
        monitorDevice = "";
        // Keep the line open: the next client tick retunes it from meter mode back to VAD mode,
        // which avoids a costly JavaSound device close/open cycle.
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
        tryImmediateBargeIn(minecraft);
    }

    /**
     * Interrupts the maid as soon as the volume gate reports a speech onset. This deliberately does
     * not wait for ASR or the decision service: whether the words turn out to be usable is judged
     * afterwards, but the maid must stop talking the moment the player starts.
     */
    private static void tryImmediateBargeIn(Minecraft minecraft) {
        if (immediateBargeInSent || !isTtsActive()
                || !isFreeChatEnabled()
                || !"immediate".equals(TouhouAIFunConfig.freeChatBargeIn())) {
            return;
        }
        immediateBargeInSent = true;
        QueuedTTSPlaybackManager.interrupt();
        AIFunStreamSoundManager.interrupt();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        int stopped = 0;
        try {
            double range = Math.max(1.0D, Math.min(256.0D, AIConfig.MAID_CAN_CHAT_DISTANCE.get()));
            for (EntityMaid maid : minecraft.level.getEntitiesOfClass(EntityMaid.class,
                    minecraft.player.getBoundingBox().inflate(range),
                    maid -> maid.isAlive() && maid.isOwnedBy(minecraft.player))) {
                AIFunNetwork.sendMaidSpeechStop(maid.getId());
                stopped++;
            }
        } catch (RuntimeException ignored) {
        }
        TouhouAIFun.LOGGER.info("Free chat barge-in: gate onset stopped maid TTS (maids={})", stopped);
    }

    public static void suspendForPushToTalk() {
        pushToTalkSuspended = true;
        pushToTalkReleaseSeen = false;
        immediateBargeInSent = false;
        captureStopDeadline = 0L;
        if (!monitorActive) {
            stopCapture();
        }
    }

    public static void resumeAfterPushToTalk() {
        pushToTalkSuspended = false;
        pushToTalkReleaseSeen = true;
    }

    public static void onFeedback(String status, int maidId) {
        awaitingFeedback = false;
        if (status == null || status.isBlank()) {
            return;
        }
        TouhouAIFun.LOGGER.info("Free chat verdict from server: status={} maidId={}", status, maidId);
        if (!isFreeChatEnabled()) {
            return;
        }
        if ("superseded".equals(status)) {
            return;
        }
        if ("self_echo".equals(status)) {
            HISTORY.removeLast();
            notice("status.self_echo", true);
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
        notice("status." + status, true);
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
            if (!CAPTURE.isRunning() || !Objects.equals(captureDevice, device)) {
                CAPTURE.setMonitorMode(false);
                CAPTURE.start(device, segmenter, chatListener());
                captureDevice = device;
                captureMonitor = false;
                TouhouAIFun.LOGGER.info("Free chat listening: device=\"{}\" threshold={} silenceMs={}",
                        device, threshold, TouhouAIFunConfig.FREE_CHAT_SILENCE_MILLIS.get());
                return;
            }
            if (captureMonitor) {
                // Coming back from the STT meter: reuse the open line instead of re-opening it.
                if (!CAPTURE.retune(device, segmenter, chatListener(), false)) {
                    CAPTURE.start(device, segmenter, chatListener());
                    TouhouAIFun.LOGGER.info("Free chat listening: device=\"{}\" threshold={} silenceMs={}",
                            device, threshold, TouhouAIFunConfig.FREE_CHAT_SILENCE_MILLIS.get());
                }
                captureMonitor = false;
            }
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
            if (!CAPTURE.isRunning() || !Objects.equals(captureDevice, monitorDevice)) {
                lastMonitorAttemptAt = System.currentTimeMillis();
                CAPTURE.setMonitorMode(true);
                CAPTURE.start(monitorDevice, null, monitorListener());
                captureDevice = monitorDevice;
                captureMonitor = true;
                return;
            }
            if (!captureMonitor) {
                // Entering the STT meter from free chat: reuse the open line.
                lastMonitorAttemptAt = System.currentTimeMillis();
                if (!CAPTURE.retune(monitorDevice, null, monitorListener(), true)) {
                    CAPTURE.setMonitorMode(true);
                    CAPTURE.start(monitorDevice, null, monitorListener());
                }
                captureMonitor = true;
            }
        } catch (RuntimeException unavailable) {
            lastMonitorAttemptAt = System.currentTimeMillis();
        }
    }

    private static void stopCapture() {
        captureStopDeadline = 0L;
        pendingScene = null;
        pendingSceneAt = 0L;
        if (CAPTURE.isRunning()) {
            CAPTURE.stop();
        }
        captureDevice = "";
        captureMonitor = false;
    }

    /** The onset snapshot is preferred; the end-of-silence snapshot is only a fallback. */
    @Nullable
    private static PlayerViewScene.Capture takePendingScene(Minecraft minecraft) {
        PlayerViewScene.Capture onset = pendingScene;
        long at = pendingSceneAt;
        pendingScene = null;
        pendingSceneAt = 0L;
        if (onset != null && System.currentTimeMillis() - at <= 30000L) {
            return onset;
        }
        return PlayerViewScene.capture(minecraft);
    }

    private static VadCapture.Listener chatListener() {
        return new VadCapture.Listener() {
            @Override
            public void onSpeechStart() {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft == null || minecraft.player == null || minecraft.level == null) {
                    return;
                }
                // Barge-in reacts to the gate onset itself, before ASR or the decision service:
                // whether the words turn out to be useful is decided later.
                // This is a captured onset event. Do not recheck CAPTURE.isSpeechActive(): a short
                // interruption can already have ended by the time this task reaches the client.
                tryImmediateBargeIn(minecraft);
                // Snapshot the view when the owner starts speaking, not after the trailing silence:
                // people often look at the maid while talking and look away when they stop.
                pendingScene = PlayerViewScene.capture(minecraft);
                pendingSceneAt = System.currentTimeMillis();
            }

            @Override
            public void onSegment(byte[] wav, int speechMillis, int peak) {
                handleSegment(wav, speechMillis, peak);
            }

            @Override
            public void onFailure(String reason) {
                lastMicFailureAt = System.currentTimeMillis();
                TouhouAIFun.LOGGER.warn("Free chat microphone unavailable: {}", reason);
                if (!monitorActive) {
                    if ("mic_busy".equals(reason)) {
                        notice("status.mic_busy", true);
                    } else if ("mic_error".equals(reason)) {
                        notice("status.mic_error", true);
                    } else {
                        notice("status.mic_missing", true);
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
        if (captureStopDeadline != 0L || monitorActive || !isFreeChatEnabled()
                || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (inFlightAsr >= MAX_IN_FLIGHT_ASR) {
            TouhouAIFun.LOGGER.info("Free chat speech captured but {} recognitions are still in flight", inFlightAsr);
            notice("status.busy", true);
            return;
        }
        var site = SttSiteSelection.resolveBuffered();
        if (site == null) {
            TouhouAIFun.LOGGER.warn("Free chat speech captured but no buffered STT site is selected");
            notice("status.stt_unsupported", true);
            return;
        }
        PlayerViewScene.Capture scene = takePendingScene(minecraft);
        if (scene == null) {
            TouhouAIFun.LOGGER.warn("Free chat speech captured but no owned maid is nearby");
            notice("status.no_maid", true);
            return;
        }
        TouhouAIFun.LOGGER.info("Free chat speech captured: speechMs={} peak={} candidates={}",
                speechMillis, peak, scene.candidateMaidIds().size());
        BufferedSTTClient client;
        try {
            if (!(site.client() instanceof BufferedSTTClient buffered)) {
                TouhouAIFun.LOGGER.warn("Free chat speech captured but the STT site cannot buffer audio");
                notice("status.stt_unsupported", true);
                return;
            }
            client = buffered;
        } catch (RuntimeException unavailable) {
            TouhouAIFun.LOGGER.warn("Free chat could not build the STT client: {}",
                    unavailable.getClass().getSimpleName());
            notice("status.stt_failed", true);
            return;
        }
        inFlightAsr++;
        try {
            client.transcribe(wav, new ResponseCallback<>() {
                @Override
                public void onFailure(@Nullable HttpRequest request, Throwable throwable, int errorCode) {
                    minecraft.execute(() -> {
                        inFlightAsr = Math.max(0, inFlightAsr - 1);
                        TouhouAIFun.LOGGER.warn("Free chat speech recognition failed: {} status={}",
                                throwable == null ? "unknown" : throwable.getClass().getSimpleName(),
                                errorCode);
                        notice("status.stt_failed", true);
                    });
                }

                @Override
                public void onSuccess(String chatText) {
                    minecraft.execute(() -> {
                        inFlightAsr = Math.max(0, inFlightAsr - 1);
                        int chars = chatText == null ? 0 : chatText.codePointCount(0, chatText.length());
                        TouhouAIFun.LOGGER.info("Free chat speech recognized: chars={} speechMs={}",
                                chars, speechMillis);
                        handleTranscript(chatText, speechMillis, scene);
                    });
                }
            });
        } catch (RuntimeException unavailable) {
            inFlightAsr = Math.max(0, inFlightAsr - 1);
            TouhouAIFun.LOGGER.warn("Free chat could not start the STT request: {}",
                    unavailable.getClass().getSimpleName());
            notice("status.stt_failed", true);
        }
    }

    private static void handleTranscript(String chatText, int speechMillis, PlayerViewScene.Capture scene) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isFreeChatEnabled() || minecraft.player == null || minecraft.level == null) {
            return;
        }
        String transcript = FreeChatDecision.sanitize(chatText, 2000);
        if (transcript.isBlank()) {
            TouhouAIFun.LOGGER.info("Free chat speech recognized but the text was blank");
            notice("status.empty", true);
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
            TouhouAIFun.LOGGER.warn("Free chat could not send the utterance packet: {}",
                    unavailable.getClass().getSimpleName());
            notice("status.stt_failed", true);
            return;
        }
        awaitingFeedback = true;
        awaitingSince = System.currentTimeMillis();
        TouhouAIFun.LOGGER.info("Free chat utterance sent: sequence={} candidates={} transcriptChars={} "
                        + "sceneChars={} recent={} language={} speechMs={}",
                sequence - 1, scene.candidateMaidIds().size(), transcript.codePointCount(0, transcript.length()),
                scene.summary().codePointCount(0, scene.summary().length()), recent.size(), language,
                Math.max(0, speechMillis));
        String preview = FreeChatDecision.sanitize(transcript, 48);
        notice("heard", true, preview);
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
        FreeChatNotifier.show(
                Component.translatable("gui.touhou_aifun.free_chat." + key, args));
    }
}
