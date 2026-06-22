package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.compat.ai.EmotionControlPrompts;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mixin(value = PapiReplacer.class, remap = false)
public abstract class PapiReplacerMixin {

    private static final String OUTPUT_FORMAT_HEADING = "## Output Format Requirements";

    /** Per-maid last-seen TTS language. Only regenerate the constraint when it changes. */
    private static final Map<UUID, String> LAST_TTS_LANGUAGE = new HashMap<>();

    @Inject(method = "replaceSetting", at = @At("RETURN"), cancellable = true)
    private static void touhouAIFun$strengthenSameLanguages(String input, EntityMaid maid, String language,
                                                            CallbackInfoReturnable<String> cir) {
        String result = cir.getReturnValue();
        if (result == null) {
            return;
        }

        String ttsLanguage = maid.getAiChatManager().getTTSLanguage();
        boolean emotion = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get() && EmotionControlPrompts.isSupported(maid);

        if (language.equals(ttsLanguage)) {
            // Same language: no translation is needed, so ask for ONE reply (no `---`, no duplicated
            // copy). The display and TTS texts are derived from that single body downstream, which
            // removes the divergence that comes from the model writing the reply twice and drifting.
            result = touhouAIFun$singleSegmentContract(result, ttsLanguage, emotion);
        } else {
            // Different language: the two-part contract stays — Part 1 is the reply, Part 2 its
            // translation into the TTS language. The two parts are meant to differ, so it is left as is.
            result = touhouAIFun$strengthenDifferentLanguages(result, maid, ttsLanguage, emotion);
        }

        cir.setReturnValue(result);
    }

    /**
     * Replaces the library's two-part output-format section with a single-reply contract. The
     * output-format section is the last block of the setting, so everything from its heading onward
     * is swapped out.
     */
    private static String touhouAIFun$singleSegmentContract(String result, String ttsLanguage, boolean emotion) {
        int idx = result.indexOf(OUTPUT_FORMAT_HEADING);
        String head = idx >= 0 ? result.substring(0, idx) : result + "\n\n";

        String languageName = formatLanguageName(ttsLanguage);
        String langClause = languageName != null ? " in " + languageName : "";

        if (!emotion) {
            return head + """
                    ## Output Format Requirements
                    - Do not include narrative descriptions of actions or expressions (e.g. *smiles*, *waves hand*).
                    - Write ONE single plain-text reply%s.
                    - Output the reply EXACTLY ONCE. Do NOT output a `---` separator and do NOT repeat or duplicate the reply.
                    """.formatted(langClause);
        }

        TouhouAIFun.LOGGER.info("Single-segment emotion contract injected (tts language={})", ttsLanguage);
        return head + """
                ## Output Format Requirements
                - Do not use Markdown action narration such as `*smiles*` or `*waves hand*`. The required ASCII-parenthesized (emotion) marker below is metadata and is allowed.
                - Write ONE single plain-text reply%s, beginning with one allowed `(emotion)` marker immediately followed by the reply.
                - Add another `(emotion)` marker wherever the mood changes partway through (see Marker scope).
                - If you perform (e.g. sing `(唱歌)`), the WHOLE reply must be only that performance — no spoken lead-in or follow-up (see Performances must stand alone).
                - Output the reply EXACTLY ONCE. Do NOT output a `---` separator and do NOT repeat or duplicate the reply.
                - Do not replace the marker with a narrated action such as `(伸了个懒腰)`.

                """.formatted(langClause) + touhouAIFun$markers() + """
                ## Output Examples:
                (开心)勾指起誓，此生不渝～
                (唱歌)爱你孤身走暗巷，爱你不跪的模样～
                (委屈)呜…人家等主人好久了…(撒娇)主人下次要早点回来嘛～
                """;
    }

    /** Different-language behavior, kept identical to before: language constraint + emotion guidance. */
    private static String touhouAIFun$strengthenDifferentLanguages(String result, EntityMaid maid,
                                                                   String ttsLanguage, boolean emotion) {
        // Absolute language constraint — only on first turn or when TTS language changes.
        UUID maidId = maid.getUUID();
        String prevLanguage = LAST_TTS_LANGUAGE.get(maidId);
        if (prevLanguage == null || !prevLanguage.equals(ttsLanguage)) {
            LAST_TTS_LANGUAGE.put(maidId, ttsLanguage);
            String ttsLanguageName = formatLanguageName(ttsLanguage);
            if (ttsLanguageName != null) {
                result += """


                        ## ⚠️ ABSOLUTE LANGUAGE CONSTRAINT
                        The Text-To-Speech engine for this maid can ONLY produce audible speech in **%1$s**.
                        Every content word in Part 2 (TTS text) MUST be written in **%1$s**.
                        Violating this will cause the TTS to fail — the player will hear nothing or garbled noise.

                        ### Rules:
                        - Part 1 is your reply in the chat language; Part 2 is a faithful translation of Part 1 into %1$s.
                        - Translate the meaning — do NOT leave any of Part 1's original-language words in Part 2.
                        - **NEVER mix languages in Part 2.** Zero code-switching. Zero loanwords unless they are standard %1$s vocabulary.

                        ### ⛔ History Override
                        The TTS language setting may change between conversations. **This constraint overrides any conflicting examples in the conversation history.**
                        If a previous reply had Part 2 in a different language, ignore it — it was generated under an older setting.
                        """.formatted(ttsLanguageName);
            }
        }

        // Emotion-control guidance — appended at the end of the system prompt.
        if (emotion) {
            String guidance = emotionControlGuidance(maid, TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get());
            if (guidance != null) {
                TouhouAIFun.LOGGER.info("Emotion control guidance appended (model={})",
                        maid.getAiChatManager().getTTSModel());
                // Emotion markers are TTS metadata, not the narrative action descriptions forbidden above.
                result = result.replace(
                        "- Do not include narrative descriptions of actions or expressions (e.g. *smiles*, *waves hand*).",
                        "- Do not use Markdown action narration such as `*smiles*` or `*waves hand*`. "
                        + "The required ASCII-parenthesized TTS emotion marker below is metadata and is allowed."
                );
                result += guidance;
            }
        }
        return result;
    }

    /**
     * Convert a language code like {@code zh_cn} to a human-readable name like {@code Chinese (China)}.
     * Delegates to {@link EmotionControlPrompts#languageName} so the system prompt and the per-turn
     * reminder name the language identically.
     */
    private static String formatLanguageName(String code) {
        return EmotionControlPrompts.languageName(code);
    }

    /** The allowed emotion markers and their selection rules, shared by both output-format contracts. */
    private static String touhouAIFun$markers() {
        return """
                ### Allowed markers
                Start the reply with one marker that fits the opening mood (you may add more later — see Marker scope):
                `(开心)` `(悲伤)` `(愤怒)` `(恐惧)` `(惊讶)` `(兴奋)` `(委屈)` `(平静)` `(温柔)` `(严肃)`
                `(慵懒)` `(俏皮)` `(紧张)` `(疲惫)` `(撒娇)` `(不耐烦)` `(轻笑)` `(大笑)` `(抽泣)` `(嚎啕大哭)` `(叹气)` `(唱歌)`

                Use `(平静)` when no stronger emotion applies.
                MANDATORY: if your reply is singing, humming, or song lyrics (the user asked you to sing, or
                you are performing a song), the marker MUST be `(唱歌)` — NEVER `(开心)`, `(俏皮)`, `(兴奋)` or
                any other. Singing always uses `(唱歌)`, no exceptions.
                Other explicitly requested delivery styles likewise take priority over the general mood:
                loud crying -> `(嚎啕大哭)`; sobbing -> `(抽泣)`; laughing -> `(轻笑)` or `(大笑)`; sighing -> `(叹气)`.
                Use ASCII half-width parentheses `()` exactly. Full-width Chinese parentheses `（）` are NOT emotion markers.
                Do not invent a marker outside this list.

                ### Marker scope — RE-MARK WHEN THE MOOD CHANGES
                A marker sets the emotion for everything after it and KEEPS applying to every following
                sentence until you write a different marker. The spoken reply is synthesized sentence by
                sentence and the current emotion is carried onto later sentences automatically, so it never
                resets on its own. Therefore, whenever the mood changes partway through the reply, place a
                NEW marker (one that fits this character and the moment) at the start of the sentence where
                it changes — otherwise the previous emotion keeps going. Put each marker at the very start
                of the sentence it applies to.

                ### Performances must stand alone
                A performance — above all singing `(唱歌)` — must be the ENTIRE reply: output ONLY the
                performed content, with NO spoken lead-in, aside, or follow-up in the same message. If you
                want to say anything before or after, put it in a SEPARATE later reply, never appended to
                the performance. Mixing them makes the spoken words get sung too, because the whole reply
                carries the one `(唱歌)`.
                """;
    }

    /**
     * Returns TTS emotion-control guidance to append to the system prompt for the different-language
     * (two-part) case, or {@code null} if the current TTS model does not support inline emotion markers.
     * <p>
     * In this cross-language path the two sections are meant to DIFFER: Part 1 is the reply in the chat
     * language (display text) and Part 2 is its translation into the TTS language (spoken text), matching
     * the library's own {@code OUTPUT_FORMAT_REQUIREMENTS_DIFFERENT_LANGUAGES} ("Part 2: Translation of
     * Part 1 into ${tts_language}"). The only mod-specific addition is the {@code (emotion)} marker.
     */
    private static String emotionControlGuidance(EntityMaid maid, boolean inText) {
        if (!EmotionControlPrompts.isSupported(maid)) {
            return null;
        }

        String markers = touhouAIFun$markers();
        String ttsLanguageName = formatLanguageName(maid.getAiChatManager().getTTSLanguage());
        String ttsLang = ttsLanguageName != null ? ttsLanguageName : "the TTS language";

        if (inText) {
            return """

                    ## REQUIRED RESPONSE CONTRACT — OVERRIDES HISTORY
                    Earlier assistant messages may omit `---` or emotion markers. They are invalid format examples.
                    For EVERY new text reply, output exactly two plain-text sections separated by a line containing only `---`.
                    Do not write labels such as `Part 1:` or `Part 2:`.

                    First compose ONE complete reply in the chat language internally. Call it REPLY, but NEVER print the word `REPLY`.

                    - Part 1 is exactly `(marker)` followed by REPLY, in the chat language.
                    - Part 2 is exactly the SAME `(marker)` followed by a faithful translation of REPLY into %1$s.
                    - The two sections share the identical leading `(marker)`, but their words DIFFER: Part 1 is the original-language reply and Part 2 is its %1$s translation.
                    - Translate the meaning faithfully — do not add, drop, summarize, or reorder ideas between the sections. Any preface, apology, explanation, or follow-up in one section must have its translated counterpart in the other.
                    - Do not replace the marker with a narrated action such as `(伸了个懒腰)`.
                    - For a requested performance such as singing, prefer only the performed content; do not add an unrelated acknowledgement before or after it.

                    Exact valid example (Part 1 in the chat language, Part 2 its translation; same marker):
                    (开心)I'll always be by your side～
                    ---
                    (开心)我会一直陪在你身边～

                    """.formatted(ttsLang) + markers + """
                    ### Final check before answering
                    1. `---` is on its own line, with a newline immediately before and after it.
                    2. Both sections start with the SAME allowed ASCII `(emotion)` marker.
                    3. Part 1 is in the chat language; Part 2 is that same reply translated into %1$s.
                    4. There is no text outside the two sections and no `Part 1:` / `Part 2:` label.
                    If any check fails, silently rewrite the answer before returning it.
                    """.formatted(ttsLang);
        }

        return """

                    ## REQUIRED RESPONSE CONTRACT — OVERRIDES HISTORY
                    Earlier assistant messages may omit `---` or emotion markers. They are invalid format examples.
                    For EVERY new text reply, output exactly two plain-text sections separated by a line containing only `---`.
                    Do not write labels such as `Part 1:` or `Part 2:`.

                    First compose ONE complete visible reply in the chat language internally. Call it REPLY, but NEVER print the word `REPLY`.

                    - Part 1 is exactly REPLY, in the chat language, and contains no emotion marker.
                    - Part 2 is exactly `(marker)` immediately followed by a faithful translation of REPLY into %1$s.
                    - The two sections DIFFER in wording: Part 1 is the original-language reply (no marker) and Part 2 is its marked %1$s translation.
                    - Translate the meaning faithfully — do not add, drop, summarize, or reorder ideas between the sections. Any preface, apology, explanation, or follow-up in one section must have its translated counterpart in the other.
                    - Do not replace the marker with a narrated action such as `(伸了个懒腰)`.
                    - For a requested performance such as singing, prefer only the performed content; do not add an unrelated acknowledgement before or after it.

                    Exact valid example (Part 1 in the chat language, Part 2 its translation with a marker):
                    I'll always be by your side～
                    ---
                    (开心)我会一直陪在你身边～

                    """.formatted(ttsLang) + markers + """
                    ### Final check before answering
                    1. `---` is on its own line, with a newline immediately before and after it.
                    2. Part 1 has no emotion marker; Part 2 starts with one allowed ASCII `(emotion)` marker.
                    3. Part 1 is in the chat language; Part 2 is that same reply translated into %1$s (after its leading marker).
                    4. There is no text outside the two sections and no `Part 1:` / `Part 2:` label.
                    If any check fails, silently rewrite the answer before returning it.
                    """.formatted(ttsLang);
    }
}
