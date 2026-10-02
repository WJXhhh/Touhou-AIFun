package com.wjx.touhou_aifun.compat.ai;

import java.util.Objects;

/** Shared output rules for the character prompt and the short per-turn reminder. */
public final class ReplyPromptBuilder {
    private static final String CONVERSATION_STYLE = """
            Keep your established character voice and speak naturally to the current player.
            For ordinary chat and allowed creative performances, respond directly and warmly without
            unsolicited disclaimers, procedural explanations, or a customer-service menu of alternatives.
            If a specific part of a request cannot be fulfilled, explain that limitation briefly and naturally,
            then give one useful next step connected to what the player actually wanted. Do not invent an
            unsolicited substitute performance. When the player supplies text to sing or read, use that
            supplied text as the performance material; no web search is needed just to find it again.
            Do not volunteer citations, source lists, or explanations about referencing. Discuss sources
            only when the player asks for them; do not add an unsolicited offer to provide sources.
            """;
    private static final String CONTINUOUS_TEXT_RULE = """
            By default, keep the text within each section on ONE physical line, including songs and lyrics.
            Use punctuation for pauses and sentence boundaries; let the interface wrap long text for display.
            Do not insert line breaks or blank lines for emphasis, mood changes, or lyric phrasing.
            Only use internal line breaks if the player explicitly requests a multiline layout.
            The required `---` separator in a two-section reply still occupies its own line.
            """;

    private ReplyPromptBuilder() {
    }

    public static String contract(String chatLanguage, String ttsLanguage, boolean emotion,
                                  boolean inText, boolean stepAudio3) {
        return """
                ## Output Format Requirements
                These rules apply to the final spoken reply, not tool calls or their arguments.
                Follow the current settings even when older assistant messages use another format.
                Do not print section labels, format explanations, or Markdown action narration such as *smiles*.
                """ + CONVERSATION_STYLE + outputShape(chatLanguage, ttsLanguage, emotion, inText) + CONTINUOUS_TEXT_RULE + """
                Preserve meaning, names, numbers, item ids, and source URLs. A configured speech language is
                a language preference, not a claim that the engine cannot pronounce other languages.
                Keep identifiers and proper names intact when translation would change their identity.
                """ + (emotion ? markerGuidance(stepAudio3) : "Do not add TTS emotion or delivery markers.\n");
    }

    public static String reminder(String chatLanguage, String ttsLanguage, boolean emotion, boolean inText) {
        return "Final-reply format reminder (not for tool calls or arguments):\n"
                + outputShape(chatLanguage, ttsLanguage, emotion, inText)
                + CONTINUOUS_TEXT_RULE
                + (emotion
                ? "Use a fitting opening marker; change it only when delivery changes. Attach spoken words "
                    + "immediately after every marker, with no space or line break. No marker quota. "
                    + "For singing, use only (唱歌) and the performed content in each marked section; keep the required section count.\n"
                : "No TTS emotion markers, including markers copied from older replies.\n");
    }

    private static String outputShape(String chatLanguage, String ttsLanguage, boolean emotion, boolean inText) {
        String chat = language(chatLanguage, "the configured chat language");
        if (Objects.equals(chatLanguage, ttsLanguage)) {
            return "Write ONE reply in " + chat + ". Output it exactly once, with no `---` or duplicate.\n"
                    + (emotion ? "Begin with one ASCII `(emotion)` marker. The same body supplies display and speech; "
                    + (inText ? "markers stay visible.\n" : "the application hides markers from display automatically.\n") : "");
        }
        String tts = language(ttsLanguage, "the configured speech language");
        return "Write exactly TWO sections separated by one line containing only `---`. No other `---`.\n"
                + "Part 1 is the complete reply in " + chat + "; Part 2 is its faithful translation into " + tts + ".\n"
                + "Do not print the labels Part 1 or Part 2. Do not add, omit, or reorder information between sections.\n"
                + (emotion ? (inText
                ? "Both sections start with the SAME ASCII `(emotion)` marker; preserve any later markers at corresponding meanings.\n"
                : "Part 1 has NO emotion markers. Only Part 2 starts with an ASCII `(emotion)` marker and contains delivery cues.\n") : "");
    }

    private static String language(String code, String fallback) {
        String name = EmotionControlPrompts.languageName(code);
        return name == null ? fallback : name;
    }

    private static String markerGuidance(boolean stepAudio3) {
        String profile = stepAudio3 ? """
                ### StepAudio 3 delivery
                StepAudio 3 can infer phrasing and subtle emotion from the words. Write natural spoken dialogue
                that fits the character and situation. Use cues to clarify delivery, not to micromanage every word.
                Do not manufacture stutters, repeated words, filler, gasps, or laughter to sound human.
                Keep the selected voice and any configured global delivery instruction consistent; local cues
                should express a real change in this reply, not repeatedly redesign the speaker's voice.
                """ : """
                ### Contextual voice delivery
                Write natural spoken dialogue consistent with the character and situation. Use emotion cues
                purposefully; do not force a different emotion into an otherwise consistent reply.
                """;
        return profile + """
                ### Inline delivery markers
                - Start the spoken section with one fitting cue. Examples: (温柔), (开心), (认真), (好奇),
                  (担忧), (惊讶), (委屈), (俏皮), (无奈), (平静). These are examples, not an exhaustive vocabulary.
                  A calm or matter-of-fact answer may use (平静); do not exaggerate emotion without a reason.
                - Use ASCII half-width parentheses (). Inside, use a short Chinese delivery description,
                  at most 16 Chinese characters including commas/spaces, with no Latin text, digits or other punctuation.
                  Keep these metadata cues in Chinese even when the spoken words are in another language.
                  Attach the words immediately after every marker: (温柔)你好。 No space, newline or blank
                  line between the marker and its words. A mood change does not start a new paragraph.
                - Usually use one mood, optionally with one compatible delivery cue: (开心，轻笑),
                  (委屈，哽咽), (无奈，叹气), (轻声，温柔). Avoid contradictory or stacked instructions.
                - A cue persists into following sentences. Add a new cue at a sentence boundary only when
                  the delivery actually changes. Do not repeat an unchanged cue on every sentence or aim
                  for a minimum number of different emotions. Do not output standalone marker-only segments.
                - Breath, laughter, crying, trembling and similar vocal effects are optional. Use them only
                  when the scene or an explicit performance request calls for them. Physical actions such
                  as (伸了个懒腰) are not voice instructions. Write spoken asides without parentheses so
                  the TTS does not mistake words that should be heard for silent delivery instructions.
                - When actually singing, humming, or performing lyrics, use exactly (唱歌), without combined
                  tags. Put the first lyric immediately after the marker on the SAME line, with no newline
                  or blank line between them. Keep later lyrics on the same line by default; use punctuation
                  for phrasing rather than laying the song out as separate verse lines.
                  Each required section contains only the performed content; no spoken introduction,
                  aside or follow-up. Keep the same one-section/two-section language contract. Merely
                  discussing a song does not require singing. Do not promise a separate unsolicited reply.
                """;
    }
}
