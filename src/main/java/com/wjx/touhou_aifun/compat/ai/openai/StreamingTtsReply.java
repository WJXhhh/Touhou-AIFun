package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSystemServices;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSAudioToClientMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.compat.ai.tts.SentenceTextSplitter;
import com.wjx.touhou_aifun.network.AIFunNetwork;

import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Speaks a streaming text reply sentence-by-sentence <em>while the model is still generating</em>.
 * <p>
 * As visible content arrives, the TTS-text region (the part after the {@code ---} separator) is
 * split into sentences and each completed sentence is queued for synthesis. Synthesis is strictly
 * <strong>sequential</strong> — only one request is in flight at a time and audio is sent to the
 * client in sentence order — because the client plays queued audio in arrival order, so parallel
 * synthesis (fast short sentences finishing before slow long ones) would scramble playback.
 * <p>
 * It owns the full text-reply finalization (takeover, chat bubble, history) for the common case of
 * a network TTS provider, replicating {@code LLMCallbackMixin#onSuccess}, so it must
 * <strong>not</strong> be combined with {@link LLMCallback#onSuccess}. It bows out (see
 * {@link #isUsable()}) for system/local TTS and the no-TTS case, letting the caller fall back to
 * the normal {@code onSuccess} path.
 */
final class StreamingTtsReply {
    private static final int MAX_CHUNK_CODE_POINTS = 1_000;

    private final LLMCallback callback;
    private final EntityMaid maid;
    private final MaidAIChatManager chatManager;
    private final UUID maidId;
    /** Same-language replies arrive as one body with no {@code ---}: the whole body is the TTS text. */
    private final boolean singleSegment;
    /** Whether the chat bubble keeps the leading {@code (emotion)} marker. */
    private final boolean showMarkerInChat;
    /**
     * Emotion control is on for an emotion-aware provider, so the leading {@code (emotion)} marker is a
     * control token the engine consumes (not spoken). The TTS text is split into sentences and each is
     * synthesized as its own request, so only the first sentence would carry the marker; we therefore
     * carry the active marker onto every following sentence so the whole reply keeps the same emotion.
     */
    private final boolean propagateEmotion;
    /** The marker (e.g. {@code (开心)}) carried onto sentences that do not start with their own. */
    private String activeMarker = StringUtils.EMPTY;

    private final boolean usable;
    private final TTSClient client;
    private final TTSConfig config;

    /** Sentences awaiting synthesis, in order. */
    private final Deque<String> pending = new ArrayDeque<>();
    /** How many sentences of the TTS text have already been queued (so we never re-queue). */
    private int queuedSentences;
    /** True while a synthesis request is in flight; keeps synthesis strictly sequential. */
    private boolean synthesizing;

    private boolean started;
    private volatile int generation;

    StreamingTtsReply(LLMCallback callback, boolean singleSegment, boolean showMarkerInChat,
                      boolean propagateEmotion) {
        this.callback = callback;
        this.maid = callback.getMaid();
        this.chatManager = callback.getChatManager();
        this.maidId = this.maid.getUUID();
        this.singleSegment = singleSegment;
        this.showMarkerInChat = showMarkerInChat;
        this.propagateEmotion = propagateEmotion;

        TTSSite site = this.chatManager.getTTSSite();
        boolean ttsOn = AIConfig.TTS_ENABLED.get() && site != null && site.enabled();
        TTSClient resolvedClient = ttsOn ? site.client() : null;
        // System/local TTS uses a different (local sound) path; let the normal onSuccess handle it.
        if (ttsOn && resolvedClient != null && !(resolvedClient instanceof TTSSystemServices)) {
            this.usable = true;
            this.client = resolvedClient;
            String model = this.chatManager.getTTSModel();
            String lang = "en";
            String[] split = this.chatManager.getTTSLanguage().split("_");
            if (split.length >= 2) {
                lang = split[0];
            }
            this.config = new TTSConfig(model, lang);
        } else {
            this.usable = false;
            this.client = null;
            this.config = null;
        }
    }

    boolean isUsable() {
        return this.usable;
    }

    /**
     * Called as content streams in. Queues any newly-completed TTS sentences once the {@code ---}
     * separator (and real text after it) is present.
     */
    void onPartial(String visibleContent) {
        if (!this.usable) {
            return;
        }
        String ttsText;
        if (this.singleSegment) {
            // No `---`: the whole reply is the TTS text (leading marker kept). Derived identically to the
            // finalized reply, so the spoken words and the displayed words can never diverge.
            ReasoningOpenAIResponseChat partial =
                    ReasoningOpenAIResponseChat.singleSegment(visibleContent, null, this.showMarkerInChat);
            ttsText = partial.getTtsText();
            if (StringUtils.isBlank(ttsText)) {
                return;
            }
        } else {
            int separator = visibleContent.indexOf("---");
            if (separator < 0) {
                return;
            }
            // Require real (non-blank) text after the separator before we treat it as TTS text.
            String afterSeparator = visibleContent.substring(separator + 3).replace("-", StringUtils.EMPTY);
            if (StringUtils.isBlank(afterSeparator)) {
                return;
            }
            ttsText = new ReasoningOpenAIResponseChat(visibleContent, null).getTtsText();
        }

        List<String> chunks = this.ttsSentences(ttsText);
        // The last chunk may still be a partial sentence; only queue the completed ones.
        this.enqueue(chunks, chunks.size() - 1);
    }

    /**
     * Splits the TTS text into synthesis chunks. With emotion control on, each {@code (emotion)} marker
     * is additionally made to start its own chunk so a mid-reply switch marker takes effect on its own
     * synthesis request rather than being buried inside one (see {@link #carryEmotion}).
     */
    private List<String> ttsSentences(String ttsText) {
        List<String> base = SentenceTextSplitter.split(ttsText, MAX_CHUNK_CODE_POINTS);
        if (!this.propagateEmotion) {
            return base;
        }
        List<String> out = new ArrayList<>();
        for (String chunk : base) {
            out.addAll(ReasoningOpenAIResponseChat.splitAtMarkers(chunk));
        }
        return out;
    }

    /** Finalizes a complete text reply: queues any remaining sentences, then records history. */
    void finish(ReasoningOpenAIResponseChat response) {
        if (ChatFlowManager.isSuperseded(this.maidId, this.callback)) {
            // A newer request took over: keep this reply in history for context, but do not output it.
            this.runOnServer(() -> this.chatManager.addAssistantHistory(response.toString()));
            return;
        }

        List<String> chunks = this.ttsSentences(response.getTtsText());
        this.enqueue(chunks, chunks.size());
        this.runOnServer(() -> this.chatManager.addAssistantHistory(response.toString()));
        // Take over speaking (interrupt any previous reply), then surface the COMPLETE chat text once —
        // replacing the live streamed bubble. This must use the finalized reply, never a partial: the
        // early-spoken sentences only cover the start of the text.
        this.ensureStarted();
        this.showChatBubble(response.getChatText());
        this.pump();
    }

    private void enqueue(List<String> chunks, int upTo) {
        synchronized (this) {
            for (int i = this.queuedSentences; i < upTo; i++) {
                this.pending.addLast(this.carryEmotion(chunks.get(i)));
            }
            if (upTo > this.queuedSentences) {
                this.queuedSentences = upTo;
            }
        }
        this.pump();
    }

    /** Shows the finalized reply in the chat bubble, replacing the live streamed bubble. */
    private void showChatBubble(String fullChatText) {
        this.runOnServer(() -> {
            long bubbleId = this.callback.getWaitingChatBubbleId();
            this.maid.getChatBubbleManager().addLLMChatText(fullChatText, bubbleId);
        });
    }

    /**
     * Keeps the emotion consistent across a multi-sentence reply. Sentences are queued strictly in
     * order, so the first sentence's {@code (emotion)} marker is recorded and prepended to every later
     * sentence that has none. A sentence carrying its own marker updates the active one (in case the
     * model changed emotion mid-reply). When emotion control is off this is a no-op.
     */
    private String carryEmotion(String sentence) {
        if (!this.propagateEmotion) {
            return sentence;
        }
        String marker = ReasoningOpenAIResponseChat.leadingMarker(sentence);
        if (!marker.isEmpty()) {
            this.activeMarker = marker;
            return sentence;
        }
        return this.activeMarker.isEmpty() ? sentence : this.activeMarker + sentence;
    }

    /** Synthesizes the next queued sentence; only one request is ever in flight at a time. */
    private void pump() {
        if (!this.usable) {
            return;
        }
        String sentence;
        synchronized (this) {
            if (this.synthesizing || this.pending.isEmpty()) {
                return;
            }
            if (ChatFlowManager.isSuperseded(this.maidId, this.callback)) {
                this.pending.clear();
                return;
            }
            sentence = this.pending.pollFirst();
            this.synthesizing = true;
        }

        this.ensureStarted();
        int capturedGeneration = this.generation;
        this.client.play(sentence, this.config, new TTSCallback(this.maid, StringUtils.EMPTY, -1) {
            @Override
            public void onSuccess(byte[] data) {
                // Drop late audio if a newer reply has taken over speaking.
                if (ChatFlowManager.ttsGeneration(maidId) == capturedGeneration
                        && maid.level() instanceof ServerLevel serverLevel
                        && maid.getOwner() instanceof ServerPlayer player) {
                    serverLevel.getServer().submit(() ->
                            NetworkHandler.sendToClientPlayer(new TTSAudioToClientMessage(maid.getId(), data), player));
                }
                StreamingTtsReply.this.onSentenceDone();
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
                TouhouLittleMaid.LOGGER.error("Streaming TTS sentence failed: {}", throwable.getMessage());
                StreamingTtsReply.this.onSentenceDone();
            }
        });
    }

    private void onSentenceDone() {
        synchronized (this) {
            this.synthesizing = false;
        }
        this.pump();
    }

    /**
     * On the first spoken sentence: cut off the previous reply's TTS so this reply takes over speaking.
     * The chat bubble is intentionally NOT shown here — at this point only the first sentence(s) have
     * streamed in, so showing it would freeze the bubble on a truncated prefix. The live bubble is kept
     * current by {@link StreamingDisplay}; the finalized full text is shown by {@link #showChatBubble}.
     */
    private void ensureStarted() {
        synchronized (this) {
            if (this.started) {
                return;
            }
            this.started = true;
        }
        this.generation = ChatFlowManager.beginTtsTakeover(this.maidId);
        AIFunNetwork.sendInterruptTts(this.maid);
    }

    private void runOnServer(Runnable runnable) {
        if (this.maid.level() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().submit(runnable);
        } else {
            runnable.run();
        }
    }
}
