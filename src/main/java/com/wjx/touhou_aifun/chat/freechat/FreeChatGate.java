package com.wjx.touhou_aifun.chat.freechat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionProvider;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionRequestGate;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings;
import com.wjx.touhou_aifun.chat.agent.decision.NativeDecisionProvider;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunFreeChatUtteranceMessage;
import com.wjx.touhou_aifun.vision.scan.VisionDirectionMath;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Server-authoritative free-chat gate. Every non-blank transcript is checked against the maid's own
 * recent replies, then judged by the configured JEV/Perplexity decision service before any chat
 * turn starts. Decision credentials never leave the server.
 */
public final class FreeChatGate {
    private static final long MIN_SUBMIT_INTERVAL_NANOS = 800_000_000L;
    private static final long PERMANENT_BACKOFF_NANOS = 10_000_000_000L;
    private static final double VIEW_COS = Math.cos(Math.toRadians(55.0D));
    private static final double LOOK_COS = Math.cos(Math.toRadians(10.0D));

    private static final Map<UUID, Long> LATEST_SEQUENCE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SUBMIT = new ConcurrentHashMap<>();
    private static final Map<String, Long> BACKOFF = new ConcurrentHashMap<>();

    private FreeChatGate() {
    }

    public static void submit(ServerPlayer sender, AIFunFreeChatUtteranceMessage packet) {
        if (sender == null || packet == null || sender.isRemoved() || sender.server == null) {
            return;
        }
        try {
            submitChecked(sender, packet);
        } catch (RuntimeException invalid) {
            TouhouAIFun.LOGGER.warn("Free chat request rejected: {}", invalid.getClass().getSimpleName());
            feedback(sender, FreeChatDecision.REASON_DECISION_FAILED, -1);
        }
    }

    private static void submitChecked(ServerPlayer sender, AIFunFreeChatUtteranceMessage packet) {
        long now = System.nanoTime();
        UUID playerId = sender.getUUID();
        Long last = LAST_SUBMIT.get(playerId);
        if (last != null && now - last < MIN_SUBMIT_INTERVAL_NANOS) {
            feedback(sender, "rate_limited", -1);
            return;
        }
        LAST_SUBMIT.put(playerId, now);

        long previous = LATEST_SEQUENCE.getOrDefault(playerId, Long.MIN_VALUE);
        if (packet.sequence() <= previous) {
            feedback(sender, "superseded", -1);
            return;
        }
        LATEST_SEQUENCE.put(playerId, packet.sequence());

        String transcript = FreeChatDecision.sanitize(packet.transcript(), 2000);
        if (transcript.isBlank()) {
            feedback(sender, "empty", -1);
            return;
        }
        String scene = FreeChatDecision.sanitizePrompt(packet.playerScene(), 2000);
        List<String> recent = new ArrayList<>();
        for (String value : packet.recentUtterances()) {
            String sanitized = FreeChatDecision.sanitize(value, 200);
            if (!sanitized.isBlank() && recent.size() < 3) {
                recent.add(sanitized);
            }
        }

        List<Candidate> candidates = candidates(sender, packet.candidateMaidIds());
        if (candidates.isEmpty()) {
            feedback(sender, FreeChatDecision.REASON_NO_MAID, -1);
            return;
        }

        for (Candidate candidate : candidates) {
            if (SelfEchoMatcher.matches(transcript, candidate.recentReplies())) {
                feedback(sender, "self_echo", candidate.maid().getId());
                return;
            }
        }

        DecisionServiceSettings.Snapshot settings;
        try {
            settings = DecisionServiceSettings.snapshot();
        } catch (RuntimeException unavailable) {
            feedback(sender, "decision_unavailable", -1);
            return;
        }
        DecisionServiceSettings.View view = settings.view();
        if (!view.enabled() || settings.apiKey().isBlank()
                || !DecisionServiceSettings.provider(view.provider())) {
            feedback(sender, "decision_unavailable", -1);
            return;
        }
        Long blockedUntil = BACKOFF.get(view.provider());
        if (blockedUntil != null && now < blockedUntil) {
            feedback(sender, "decision_unavailable", -1);
            return;
        }
        if (!DecisionRequestGate.acquire(view.provider(), now)) {
            feedback(sender, "rate_limited", -1);
            return;
        }

        if ("after_asr".equals(TouhouAIFunConfig.freeChatBargeIn())) {
            interruptCandidates(candidates);
        }

        List<FreeChatDecision.Candidate> views = candidates.stream().map(Candidate::view).toList();
        JsonObject state = FreeChatDecision.state(transcript, packet.speechMillis(),
                sender.getGameProfile().getName(), scene, recent, views);
        JsonObject questions = FreeChatDecision.questions(views);
        Vec3 startPosition = sender.position();
        float startYaw = sender.getYRot();
        long started = System.nanoTime();
        CompletableFuture<DecisionProvider.Result> future;
        try {
            future = settings.client().evaluate(state, questions, ignored -> {
            });
        } catch (RuntimeException invalid) {
            feedback(sender, FreeChatDecision.REASON_DECISION_FAILED, -1);
            return;
        }
        future.whenComplete((result, error) -> {
            try {
                sender.server.execute(() -> finish(sender, packet, transcript, scene, recent,
                        candidates, views, settings, startPosition, startYaw, started, result, error));
            } catch (RejectedExecutionException ignored) {
            }
        });
    }

    private static void finish(ServerPlayer sender, AIFunFreeChatUtteranceMessage packet,
                               String transcript, String scene, List<String> recent,
                               List<Candidate> candidates, List<FreeChatDecision.Candidate> views,
                               DecisionServiceSettings.Snapshot settings, Vec3 startPosition,
                               float startYaw, long started, DecisionProvider.Result result,
                               Throwable error) {
        if (sender.isRemoved() || sender.server == null) {
            return;
        }
        if (!Long.valueOf(packet.sequence()).equals(LATEST_SEQUENCE.get(sender.getUUID()))) {
            return;
        }
        if (error != null) {
            handleError(sender, settings, error);
            return;
        }
        if (result == null || result.answers() == null) {
            feedback(sender, FreeChatDecision.REASON_DECISION_FAILED, -1);
            return;
        }
        FreeChatDecision.Thresholds thresholds = new FreeChatDecision.Thresholds(
                TouhouAIFunConfig.FREE_CHAT_MIN_CONFIDENCE.get(),
                TouhouAIFunConfig.FREE_CHAT_PLAYER_RELATED_CONFIDENCE.get(),
                TouhouAIFunConfig.FREE_CHAT_OTHER_RELATED_CONFIDENCE.get());
        FreeChatDecision.Verdict verdict = FreeChatDecision.accept(result.answers(), views, thresholds);
        if (!verdict.accepted() || verdict.responder() == null) {
            feedback(sender, verdict.reason(), -1);
            return;
        }
        Candidate responder = candidates.stream()
                .filter(candidate -> candidate.view().index() == verdict.responder().index())
                .findFirst().orElse(null);
        if (responder == null) {
            feedback(sender, "stale", -1);
            return;
        }
        String stale = freshness(sender, responder.maid(), startPosition, startYaw, started,
                SettingsLimit.from(settings));
        if (stale != null) {
            feedback(sender, stale, responder.maid().getId());
            return;
        }
        try {
            String ambient = FreeChatDecision.ambientFacts(verdict.target(), verdict.action(),
                    verdict.related(), scene, views, verdict.responder(), recent);
            FreeChatContext.remember(responder.maid(), transcript, ambient);
            ChatClientInfo clientInfo = new ChatClientInfo(
                    FreeChatDecision.sanitize(packet.clientLanguage(), 32),
                    responder.maid().getName().getString(), List.of());
            responder.maid().getAiChatManager().chat(transcript, clientInfo, sender);
        } catch (RuntimeException failed) {
            TouhouAIFun.LOGGER.warn("Free chat could not start the maid turn: {}",
                    failed.getClass().getSimpleName());
            feedback(sender, FreeChatDecision.REASON_DECISION_FAILED, responder.maid().getId());
            return;
        }
        TouhouAIFun.LOGGER.info("Free chat accepted: provider={} requested_model={} reported_model={} "
                        + "input_tokens={} output_tokens={} request_id={} target_index={} action={} "
                        + "target_kind={} related={}",
                settings.view().provider(), settings.view().model(), result.model(),
                result.inputTokens(), result.outputTokens(), result.requestId(),
                verdict.responder().index(), verdict.action(), verdict.target(), verdict.related());
        feedback(sender, FreeChatDecision.REASON_ACCEPTED, responder.maid().getId());
    }

    @Nullable
    private static String freshness(ServerPlayer sender, EntityMaid maid, Vec3 startPosition,
                                    float startYaw, long started, SettingsLimit limit) {
        long elapsed = System.nanoTime() - started;
        if (elapsed > limit.maxNanos()) {
            return "stale";
        }
        if (sender.position().distanceToSqr(startPosition) > 64.0D) {
            return "stale";
        }
        float yawDelta = Math.abs(wrapDegrees(sender.getYRot() - startYaw));
        if (yawDelta > 90.0F) {
            return "stale";
        }
        if (!maid.isAlive() || !maid.isOwnedBy(sender) || maid.level() != sender.level()) {
            return "stale";
        }
        double range = chatDistance() + 4.0D;
        if (sender.distanceToSqr(maid) > range * range) {
            return "too_far";
        }
        return null;
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static void handleError(ServerPlayer sender, DecisionServiceSettings.Snapshot settings,
                                    Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String reason = FreeChatDecision.REASON_DECISION_FAILED;
        if (cause instanceof NativeDecisionProvider.HttpFailure http) {
            if (http.permanent()) {
                BACKOFF.put(settings.view().provider(),
                        System.nanoTime() + PERMANENT_BACKOFF_NANOS);
                reason = "decision_unavailable";
            } else if (http.status == 429 || http.status == 529) {
                DecisionRequestGate.coolDown(settings.view().provider(), System.nanoTime());
                reason = "rate_limited";
            }
        } else if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
            reason = FreeChatDecision.REASON_DECISION_FAILED;
        } else if (cause instanceof IllegalArgumentException) {
            reason = FreeChatDecision.REASON_DECISION_FAILED;
        }
        TouhouAIFun.LOGGER.warn("Free chat decision failed: provider={} cause={}",
                settings.view().provider(), cause.getClass().getSimpleName());
        feedback(sender, reason, -1);
    }

    private static List<Candidate> candidates(ServerPlayer sender, List<Integer> ids) {
        List<Candidate> result = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        double range = chatDistance() + 2.0D;
        Vec3 view = sender.getViewVector(1.0F).normalize();
        for (int index = 0; index < ids.size(); index++) {
            int maidId = ids.get(index);
            if (!seen.add(maidId)) {
                continue;
            }
            if (!(sender.level().getEntity(maidId) instanceof EntityMaid maid)) {
                continue;
            }
            if (!maid.isAlive() || !maid.isOwnedBy(sender) || maid.level() != sender.level()) {
                continue;
            }
            double distance = sender.distanceTo(maid);
            if (distance > range) {
                continue;
            }
            Vec3 center = maid.getBoundingBox().getCenter();
            Vec3 delta = center.subtract(sender.getEyePosition(1.0F));
            double length = delta.length();
            double dot = length < 0.01D ? 1.0D : view.dot(delta.scale(1.0D / length));
            boolean inView = dot >= VIEW_COS;
            boolean lookedAt = dot >= LOOK_COS && sender.hasLineOfSight(maid);
            String direction = VisionDirectionMath.relativeDirection(sender.getX(), sender.getEyeY(),
                    sender.getZ(), sender.getYRot(), center);
            List<String> recentReplies = recentAssistant(maid, 2);
            FreeChatDecision.Candidate viewCandidate = new FreeChatDecision.Candidate(index,
                    maid.getId(), maid.getName().getString(), distance, direction, inView, lookedAt,
                    ChatFlowManager.hasActiveRequest(maid.getUUID()),
                    recentReplies.isEmpty() ? "" : recentReplies.get(0));
            result.add(new Candidate(index, maid, viewCandidate, recentReplies));
        }
        return List.copyOf(result);
    }

    private static List<String> recentAssistant(EntityMaid maid, int max) {
        List<String> result = new ArrayList<>();
        Iterator<LLMMessage> iterator = maid.getAiChatManager().getHistory().getDeque().descendingIterator();
        while (iterator.hasNext() && result.size() < max) {
            LLMMessage message = iterator.next();
            if (message.role() == Role.ASSISTANT && message.message() != null
                    && !message.message().isBlank()) {
                result.add(message.message().length() <= 2000
                        ? message.message() : message.message().substring(0, 2000));
            }
        }
        return List.copyOf(result);
    }

    private static void interruptCandidates(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            EntityMaid maid = candidate.maid();
            ChatFlowManager.interruptSpeech(maid.getUUID());
            AIFunNetwork.sendInterruptTts(maid);
        }
    }

    private static double chatDistance() {
        try {
            return Math.max(1.0D, Math.min(256.0D, AIConfig.MAID_CAN_CHAT_DISTANCE.get()));
        } catch (RuntimeException unavailable) {
            return 12.0D;
        }
    }

    private static void feedback(ServerPlayer sender, String status, int maidId) {
        AIFunNetwork.sendFreeChatFeedback(sender, status, maidId);
    }

    public static void clearAll() {
        LATEST_SEQUENCE.clear();
        LAST_SUBMIT.clear();
        BACKOFF.clear();
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId != null) {
            LATEST_SEQUENCE.remove(playerId);
            LAST_SUBMIT.remove(playerId);
        }
    }

    private record Candidate(int index, EntityMaid maid, FreeChatDecision.Candidate view,
                             List<String> recentReplies) {
    }

    private record SettingsLimit(long maxNanos) {
        private static SettingsLimit from(DecisionServiceSettings.Snapshot settings) {
            long timeout = Math.max(1000L, Math.min(30000L, settings.view().timeoutMs()));
            long max = Math.min(10_000L, timeout + 2000L);
            return new SettingsLimit(max * 1_000_000L);
        }
    }
}
