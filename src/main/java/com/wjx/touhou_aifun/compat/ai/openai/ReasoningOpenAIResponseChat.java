package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReasoningOpenAIResponseChat extends ResponseChat {
    /**
     * One leading emotion marker like {@code (开心)} — a short run of CJK characters wrapped in
     * parentheses at the very start. Matching CJK-only (not arbitrary text) keeps genuine parenthetical
     * openings (emoticons, English asides) from being mistaken for a marker. Both ASCII {@code ()} and
     * full-width {@code （）} parentheses are accepted: the prompt asks for ASCII, but models frequently
     * emit full-width ones, and an unstripped marker would otherwise leak into the chat bubble. Keep in
     * sync with the marker list in {@code PapiReplacerMixin}.
     */
    private static final Pattern LEADING_MARKER =
            Pattern.compile("^\\s*[(（][\\u4e00-\\u9fa5]{1,6}[)）]\\s*");

    /**
     * Any {@code (emotion)} marker anywhere in the text. With mid-reply emotion switches a reply can
     * carry several markers (e.g. {@code (唱歌)…(平静)…}); when the chat bubble hides markers, all of
     * them must be removed, not just the leading one.
     */
    private static final Pattern ANY_MARKER =
            Pattern.compile("[(（][\\u4e00-\\u9fa5]{1,6}[)）]");

    /**
     * A leading {@code Part 1:} / {@code Part 2:} label (or full-width colon). The prompt forbids these
     * labels, but models sometimes add them anyway; left in place they would be displayed or spoken.
     */
    private static final Pattern PART_LABEL =
            Pattern.compile("^\\s*[Pp]art\\s*[12]\\s*[:：]\\s*");

    @Nullable
    private final String reasoningContent;

    ReasoningOpenAIResponseChat(String content, @Nullable String reasoningContent) {
        super(normalizeRepeatedParts(content));
        // The two parts may carry a stray `Part 1:` / `Part 2:` label the model added against
        // instructions; drop it so it is neither shown nor spoken. Keep the original if stripping
        // would empty a part (defensive — a label-only segment should not blank the reply).
        this.chatText = stripPartLabel(super.getChatText());
        this.ttsText = stripPartLabel(super.getTtsText());
        this.reasoningContent = reasoningContent;
    }

    private ReasoningOpenAIResponseChat(String chatText, String ttsText, @Nullable String reasoningContent) {
        super(chatText, ttsText);
        this.reasoningContent = reasoningContent;
    }

    /**
     * Builds a reply from a <em>single-segment</em> model output (no {@code ---}, no duplicated body):
     * used when the chat language equals the TTS language, so no translation is needed and both texts
     * are the exact same words. The TTS text keeps the one leading {@code (emotion)} marker (TTS
     * metadata); the chat text keeps it only when {@code showMarkerInChat} is set.
     * <p>
     * This eliminates the display/TTS divergence that arises when the model is asked to write the same
     * reply twice and drifts on the second copy.
     */
    static ReasoningOpenAIResponseChat singleSegment(String content, @Nullable String reasoningContent,
                                                     boolean showMarkerInChat) {
        String body = firstSegment(content);
        String chatText = showMarkerInChat ? body : stripAllMarkers(body);
        return new ReasoningOpenAIResponseChat(chatText, body, reasoningContent);
    }

    static String normalizeRepeatedParts(@Nullable String content) {
        String value = StringUtils.defaultString(content).trim();
        String[] parts = Arrays.stream(value.split("---", -1))
                .map(String::trim)
                .toArray(String[]::new);

        for (int period = 1; period <= parts.length / 2; period++) {
            if (parts.length % period != 0 || !isRepeated(parts, period)) {
                continue;
            }
            return String.join("\n---\n", Arrays.copyOf(parts, period));
        }
        return value;
    }

    private static boolean isRepeated(String[] parts, int period) {
        for (int index = period; index < parts.length; index++) {
            if (!parts[index].equals(parts[index % period])) {
                return false;
            }
        }
        return true;
    }

    /** Drops closed {@code <think>} blocks and any trailing unclosed {@code <think>} for inline-reasoning models. */
    static String stripThink(@Nullable String content) {
        String value = StringUtils.defaultString(content).replaceAll("<think>[\\s\\S]*?</think>", "");
        int open = value.indexOf("<think>");
        return open >= 0 ? value.substring(0, open) : value;
    }

    /**
     * The reply body: reasoning stripped, and only the text before any stray {@code ---} (so an
     * accidentally duplicated reply collapses to its first copy instead of being shown/spoken twice).
     */
    static String firstSegment(@Nullable String content) {
        String value = stripThink(content);
        int separator = value.indexOf("---");
        return stripPartLabel((separator >= 0 ? value.substring(0, separator) : value).trim());
    }

    /** Removes one leading {@code (emotion)} marker, if present, so the chat bubble shows clean text. */
    static String stripLeadingMarker(@Nullable String text) {
        return LEADING_MARKER.matcher(StringUtils.defaultString(text)).replaceFirst("");
    }

    /**
     * Removes every {@code (emotion)} marker (leading and any mid-reply switch markers) so the chat
     * bubble shows clean text when markers are hidden from chat.
     */
    static String stripAllMarkers(@Nullable String text) {
        return ANY_MARKER.matcher(StringUtils.defaultString(text)).replaceAll("").trim();
    }

    /**
     * The leading {@code (emotion)} marker (e.g. {@code (开心)}) with its parentheses, or empty string
     * if the text does not start with one. Used to carry the emotion across streamed TTS sentences.
     */
    static String leadingMarker(@Nullable String text) {
        Matcher matcher = LEADING_MARKER.matcher(StringUtils.defaultString(text));
        return matcher.find() ? matcher.group().trim() : StringUtils.EMPTY;
    }

    /**
     * Splits text so that every {@code (emotion)} marker begins a piece (any text before the first
     * marker stays as its own leading piece). This guarantees each synthesized TTS chunk carries at
     * most one leading marker, so a mid-sentence switch marker (e.g. after a {@code ～}) still takes
     * effect instead of being buried inside a chunk. Returns the text unchanged when it has no marker.
     */
    static List<String> splitAtMarkers(@Nullable String text) {
        String value = StringUtils.defaultString(text);
        Matcher matcher = ANY_MARKER.matcher(value);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.isEmpty()) {
            return List.of(value);
        }
        List<String> parts = new ArrayList<>();
        if (starts.get(0) > 0 && !value.substring(0, starts.get(0)).isBlank()) {
            parts.add(value.substring(0, starts.get(0)));
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : value.length();
            parts.add(value.substring(starts.get(i), end));
        }
        return parts;
    }

    /**
     * Removes a leading {@code Part 1:} / {@code Part 2:} label the model added against instructions,
     * unless doing so would empty the segment (in which case the original is kept defensively).
     */
    static String stripPartLabel(@Nullable String text) {
        String value = StringUtils.defaultString(text);
        String stripped = PART_LABEL.matcher(value).replaceFirst("").trim();
        return stripped.isEmpty() ? value.trim() : stripped;
    }

    @Override
    public String toString() {
        return ReasoningContentCodec.encode(super.toString(), reasoningContent);
    }
}
