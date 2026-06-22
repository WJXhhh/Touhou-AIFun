package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamAccumulator;
import com.wjx.touhou_aifun.mixin.LLMCallbackAccessor;

import java.util.UUID;

/**
 * Streams a reply's "deep thinking" (reasoning) and then its answer text into the maid's head bubble
 * <em>as tokens arrive</em>, so reasoning models like DeepSeek / MiMo show their reasoning live and
 * the answer is visible while it is being typed out.
 * <p>
 * Reasoning is shown in the <em>thinking</em> ("思考中") bubble; as soon as the answer starts it
 * switches to a normal <em>text</em> bubble (updated in place) so the reply no longer looks like it
 * is still thinking. The callback's waiting-bubble id is kept pointing at whichever bubble is live,
 * so the finalizer's {@code addLLMChatText} cleanly replaces it. Once the {@code ---} separator
 * marks the displayed answer as complete, this stops and hands the bubble to the finalizer.
 */
final class StreamingDisplay {
    private static final String WAITING_KEY = "ai.touhou_little_maid.chat.chat_bubble_waiting";
    private static final long MIN_INTERVAL_MS = 120;
    private static final int MAX_DISPLAY_CHARS = 160;
    private static final int STREAM_BUBBLE_TICKS = 90 * 20;

    private final LLMCallback callback;
    private final EntityMaid maid;
    private final UUID maidId;
    /** Same-language replies arrive as one body with no {@code ---} separator. */
    private final boolean singleSegment;
    /** Whether the chat bubble keeps the leading {@code (emotion)} marker. */
    private final boolean showMarkerInChat;

    private long lastUpdateMs;
    private String lastShown = StringUtils.EMPTY;
    private boolean done;

    // Server-thread-only bubble state.
    private long liveBubbleId;
    private long answerBubbleId = -1;
    private boolean answerMode;

    StreamingDisplay(LLMCallback callback, boolean singleSegment, boolean showMarkerInChat) {
        this.callback = callback;
        this.maid = callback.getMaid();
        this.maidId = this.maid.getUUID();
        this.liveBubbleId = callback.getWaitingChatBubbleId();
        this.singleSegment = singleSegment;
        this.showMarkerInChat = showMarkerInChat;
    }

    void onUpdate(StreamAccumulator accumulator) {
        if (this.done || ChatFlowManager.isSuperseded(this.maidId, this.callback)) {
            return;
        }

        String content = accumulator.currentContent();
        // Drop closed <think> blocks and any trailing unclosed <think> so reasoning never leaks into
        // the answer region for inline-reasoning models.
        String visible = content.replaceAll("<think>[\\s\\S]*?</think>", "");
        int openThink = visible.indexOf("<think>");
        if (openThink >= 0) {
            visible = visible.substring(0, openThink);
        }

        int separator = visible.indexOf("---");
        if (!this.singleSegment && separator >= 0) {
            String afterSeparator = visible.substring(separator + 3).replace("-", StringUtils.EMPTY);
            if (StringUtils.isNotBlank(afterSeparator)) {
                // Display text is complete; the finalizer owns the bubble from here on.
                this.done = true;
                return;
            }
        }

        // Cut at any stray `---` so an accidentally duplicated reply is not shown twice.
        String answer = (separator >= 0 ? visible.substring(0, separator) : visible).strip();
        if (this.singleSegment && !this.showMarkerInChat) {
            answer = ReasoningOpenAIResponseChat.stripAllMarkers(answer);
        }
        boolean isAnswer = StringUtils.isNotBlank(answer);
        String display;
        if (isAnswer) {
            display = tail(answer);
        } else {
            String reasoning = accumulator.currentReasoning();
            if (StringUtils.isBlank(reasoning)) {
                reasoning = openThink(content);
            }
            display = StringUtils.isBlank(reasoning) ? StringUtils.EMPTY : tail(reasoning.strip());
        }

        if (StringUtils.isBlank(display) || display.equals(this.lastShown)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastUpdateMs < MIN_INTERVAL_MS) {
            return;
        }
        this.lastUpdateMs = now;
        this.lastShown = display;

        String shown = display;
        if (this.maid.level() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().submit(() -> this.apply(shown, isAnswer));
        }
    }

    private void apply(String text, boolean isAnswer) {
        if (ChatFlowManager.isSuperseded(this.maidId, this.callback)) {
            return;
        }
        ChatBubbleManager manager = this.maid.getChatBubbleManager();
        if (isAnswer) {
            if (this.answerMode && manager.getChatBubble(this.answerBubbleId) instanceof TextChatBubbleData textBubble) {
                // Update the answer text in place (no new bubble, no chat-line spam).
                textBubble.setText(Component.literal(text));
                manager.forceUpdateChatBubble();
                return;
            }
            // Switch out of the thinking bubble into a normal text bubble.
            manager.removeChatBubble(this.liveBubbleId);
            this.answerBubbleId = manager.addChatBubble(
                    TextChatBubbleData.create(STREAM_BUBBLE_TICKS, Component.literal(text),
                            IChatBubbleData.TYPE_2, IChatBubbleData.DEFAULT_PRIORITY));
            this.liveBubbleId = this.answerBubbleId;
            this.answerMode = true;
        } else {
            // Reasoning: keep the thinking bubble, refreshing its (gray) secondary text.
            this.callback.refreshWaitingChatBubble(Component.literal(text));
            this.liveBubbleId = this.callback.getWaitingChatBubbleId();
        }
        // Keep the callback's waiting-bubble id pointing at the live bubble so the finalizer's
        // addLLMChatText replaces it instead of leaving a dangling bubble.
        ((LLMCallbackAccessor) this.callback).touhouAIFun$setWaitingChatBubbleId(this.liveBubbleId);
    }

    /** Content of an unclosed {@code <think>} block (inline-reasoning models), if any. */
    private static String openThink(String content) {
        int open = content.lastIndexOf("<think>");
        if (open < 0 || content.indexOf("</think>", open) >= 0) {
            return StringUtils.EMPTY;
        }
        return content.substring(open + "<think>".length());
    }

    private static String tail(String text) {
        if (text.length() <= MAX_DISPLAY_CHARS) {
            return text;
        }
        return "…" + text.substring(text.length() - MAX_DISPLAY_CHARS);
    }
}
