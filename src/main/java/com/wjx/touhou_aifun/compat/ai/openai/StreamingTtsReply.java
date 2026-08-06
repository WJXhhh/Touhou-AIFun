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
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.compat.ai.tts.SentenceTextSplitter;
import com.wjx.touhou_aifun.network.AIFunNetwork;

import javax.annotation.Nullable;
import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Speaks a streaming text reply sentence-by-sentence <em>while the model is still generating</em>.
 * <p>
 * As visible content arrives, the TTS-text region (the part after the {@code ---} separator) is
 * split into sentences and each completed sentence is queued for synthesis. Synthesis is
 * <strong>pipelined</strong>: up to {@link #MAX_CONCURRENT_SYNTHESIS} sentences are synthesized in
 * parallel so a later sentence can be produced while an earlier one is still being made. The client
 * plays queued audio in <em>arrival</em> order, so finished audio is held in a reorder buffer and
 * released to the client <strong>strictly in sentence order</strong> — as soon as the next expected
 * sentence is ready, it (and any consecutive ready sentences after it) is flushed out at once. This
 * keeps playback in order while still letting synthesis run ahead of playback.
 * <p>
 * It owns the full text-reply finalization (takeover, chat bubble, history) for the common case of
 * a network TTS provider, replicating {@code LLMCallbackMixin#onSuccess}, so it must
 * <strong>not</strong> be combined with {@link LLMCallback#onSuccess}. It bows out (see
 * {@link #isUsable()}) for system/local TTS and the no-TTS case, letting the caller fall back to
 * the normal {@code onSuccess} path.
 */
final class StreamingTtsReply {
    private static final int MAX_CHUNK_CODE_POINTS = 1_000;
    /** Upper bound on synthesis requests in flight at once, so playback can run ahead without flooding the API. */
    private static final int MAX_CONCURRENT_SYNTHESIS = 3;
    /** Stored for a failed/silent sentence so the in-order flush can advance past it without sending anything. */
    private static final byte[] EMPTY_AUDIO = new byte[0];

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
    /**
     * How many characters of the TTS text have already been turned into queued sentences. Tracking the
     * consumed text by <em>character offset</em> (not by an index into a chunk list that is recomputed
     * every streaming frame) keeps the accounting stable: each frame only the unconsumed, completed tail
     * is split and queued, so no sentence is re-queued, dropped, or given a stale emotion marker.
     */
    private int consumedLen;
    /** Number of synthesis requests currently in flight; capped at {@link #MAX_CONCURRENT_SYNTHESIS}. */
    private int inFlight;
    /** Sequence number assigned to the next sentence dispatched for synthesis. */
    private long nextDispatchIndex;
    /** Sequence number of the next sentence whose audio is allowed to be sent to the client. */
    private long nextSendIndex;
    /** Finished audio waiting for its in-order turn (index -> audio; {@link #EMPTY_AUDIO} = failed/silent). */
    private final Map<Long, byte[]> readyAudio = new HashMap<>();

    private boolean started;
    /** Prevent a stream finalizer and a duplicate provider callback from committing twice. */
    private boolean finishScheduled;
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

        // Queue only sentences completed so far (the still-growing trailing sentence is left for later).
        this.queueUpTo(ttsText, SentenceTextSplitter.completePrefixLength(ttsText, MAX_CHUNK_CODE_POINTS));
    }

    /**
     * Splits a piece of TTS text into synthesis chunks. With emotion control on, each {@code (emotion)}
     * marker is additionally made to start its own chunk so a mid-reply switch marker takes effect on
     * its own synthesis request rather than being buried inside one (see {@link #carryEmotion}).
     */
    private List<String> piecesOf(String text) {
        List<String> base = SentenceTextSplitter.split(text, MAX_CHUNK_CODE_POINTS);
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
        // The reply is complete, so everything up to the end is a finished sentence.
        synchronized (this) {
            if (this.finishScheduled) return;
            this.finishScheduled = true;
        }
        String ttsText = response.getTtsText();
        // Do not dispatch the final queue before the server-thread turn check below. Otherwise a
        // late stream could take over TTS in the small window before B's turn is observed.
        this.queueUpTo(ttsText, ttsText.length(), false);
        this.runOnServer(() -> {
            if (ChatFlowManager.isSuperseded(this.maidId, this.callback)) {
                // A newer request took over: the response is a discarded draft, not an answer to
                // the newer user turn. Keeping it as ordinary assistant history corrupts ordering.
                synchronized (this) {
                    this.pending.clear();
                    this.readyAudio.clear();
                }
                AIFunMemoryManager.interruptCallback(this.callback);
                ChatFlowManager.finishRequest(this.maidId, this.callback);
                return;
            }

            // Commit the durable turn and legacy history on the same server thread that starts a
            // new player turn. This makes the turn check and the assistant write one ordered event.
            AIFunMemoryManager.completeCallback(this.callback, response.toString());
            ChatFlowManager.finishRequest(this.maidId, this.callback);
            this.chatManager.addAssistantHistory(response.toString());
            // Take over speaking (interrupt any previous reply), then surface the COMPLETE chat
            // text once — replacing the live streamed bubble.
            this.ensureStarted();
            this.showChatBubble(response.getChatText());
            this.pump();
        });
    }

    /**
     * Queues the completed-sentence text of {@code ttsText} between the already-consumed offset and
     * {@code completeLen}. Only the newly-completed tail is split and queued, so the emotion carried by
     * {@link #carryEmotion} stays consistent across frames and no sentence is re-queued or dropped.
     */
    private void queueUpTo(String ttsText, int completeLen) {
        this.queueUpTo(ttsText, completeLen, true);
    }

    private void queueUpTo(String ttsText, int completeLen, boolean dispatch) {
        synchronized (this) {
            int from = Math.min(this.consumedLen, ttsText.length());
            int to = Math.min(completeLen, ttsText.length());
            if (to <= from) {
                // Nothing new completed (or the text unexpectedly shrank — never un-speak what was said).
                return;
            }
            for (String piece : this.piecesOf(ttsText.substring(from, to))) {
                String spoken = this.carryEmotion(piece);
                // A marker-only piece (e.g. adjacent markers like `(唱歌)(平静)`) carries no spoken
                // text. carryEmotion has already recorded its emotion, but queuing it would waste a
                // serial synthesis slot on empty audio and stall the next real sentence — so skip it.
                if (!ReasoningOpenAIResponseChat.stripAllMarkers(spoken).isEmpty()) {
                    this.pending.addLast(spoken);
                }
            }
            this.consumedLen = to;
        }
        if (dispatch) this.pump();
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

    /**
     * Dispatches queued sentences for synthesis, keeping up to {@link #MAX_CONCURRENT_SYNTHESIS}
     * requests in flight at once. Sentences are synthesized in parallel (their audio is reordered on
     * completion by {@link #onSynthesized}), so the next sentence starts being made before the previous
     * one has finished playing.
     */
    private void pump() {
        if (!this.usable) {
            return;
        }
        List<Dispatch> toDispatch = new ArrayList<>();
        synchronized (this) {
            if (ChatFlowManager.isSuperseded(this.maidId, this.callback)) {
                this.pending.clear();
                return;
            }
            while (this.inFlight < MAX_CONCURRENT_SYNTHESIS && !this.pending.isEmpty()) {
                String sentence = this.pending.pollFirst();
                toDispatch.add(new Dispatch(this.nextDispatchIndex++, sentence));
                this.inFlight++;
            }
        }
        // Fire the requests outside the lock. ensureStarted is idempotent and only the first call does
        // the takeover and sets the generation each dispatched sentence then captures.
        for (Dispatch dispatch : toDispatch) {
            this.ensureStarted();
            this.synthesize(dispatch.index(), dispatch.sentence());
        }
    }

    private void synthesize(long index, String sentence) {
        int capturedGeneration = this.generation;
        this.client.play(sentence, this.config, new TTSCallback(this.maid, StringUtils.EMPTY, -1) {
            @Override
            public void onSuccess(byte[] data) {
                StreamingTtsReply.this.onSynthesized(index, data, capturedGeneration);
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
                TouhouLittleMaid.LOGGER.error("Streaming TTS sentence failed: {}", throwable.getMessage());
                StreamingTtsReply.this.onSynthesized(index, null, capturedGeneration);
            }
        });
    }

    /**
     * Records a finished sentence's audio, then releases every sentence that is now ready in an
     * unbroken run starting at {@link #nextSendIndex} — flushing the reorder buffer to the client in
     * sentence order. A sentence that completes out of order simply waits until the gap before it is
     * filled, so audio always arrives at the client in order even though synthesis runs ahead.
     */
    private void onSynthesized(long index, @Nullable byte[] data, int capturedGeneration) {
        List<byte[]> toSend = new ArrayList<>();
        synchronized (this) {
            this.inFlight--;
            this.readyAudio.put(index, data != null && data.length > 0 ? data : EMPTY_AUDIO);
            while (this.readyAudio.containsKey(this.nextSendIndex)) {
                byte[] audio = this.readyAudio.remove(this.nextSendIndex);
                this.nextSendIndex++;
                if (audio.length > 0) {
                    toSend.add(audio);
                }
            }
        }
        // Drop the audio if a newer reply has taken over speaking; otherwise send it in order.
        if (ChatFlowManager.ttsGeneration(this.maidId) == capturedGeneration) {
            for (byte[] audio : toSend) {
                this.sendAudio(audio);
            }
        }
        this.pump();
    }

    private void sendAudio(byte[] data) {
        if (this.maid.level() instanceof ServerLevel serverLevel
                && this.maid.getOwner() instanceof ServerPlayer player) {
            serverLevel.getServer().submit(() ->
                    NetworkHandler.sendToClientPlayer(new TTSAudioToClientMessage(this.maid.getId(), data), player));
        }
    }

    /** A sentence assigned its in-order sequence number, dispatched for parallel synthesis. */
    private record Dispatch(long index, String sentence) {
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
            // Set the generation under the lock so a parallel dispatch never captures the unset (0)
            // value after seeing started == true.
            this.generation = ChatFlowManager.beginTtsTakeover(this.maidId);
        }
        AIFunNetwork.sendInterruptTts(this.maid);
    }

    private void runOnServer(Runnable runnable) {
        if (this.maid.level() instanceof ServerLevel serverLevel) {
            if (serverLevel.getServer().isSameThread()) {
                runnable.run();
            } else {
                serverLevel.getServer().submit(runnable);
            }
        } else {
            runnable.run();
        }
    }
}
