package com.wjx.touhou_aifun.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class TouhouAIFunConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue AGENT_RUNTIME;
    public static final ForgeConfigSpec.BooleanValue AGENT_DIAGNOSTICS;
    public static final ForgeConfigSpec.ConfigValue<String> AGENT_TASK_ANNOUNCEMENTS;
    public static final ForgeConfigSpec.BooleanValue AGENT_AUTOMATIC_TOOL_SELECTION;
    public static final ForgeConfigSpec.BooleanValue AGENT_AMEND_AUTO_RESUME;
    public static final ForgeConfigSpec.IntValue AGENT_CONTEXT_INPUT_BUDGET_TOKENS;
    public static final ForgeConfigSpec.ConfigValue<String> AGENT_DECISION_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> AGENT_DEEPSEEK_EFFORT;
    public static final ForgeConfigSpec.BooleanValue DECISION_STARTUP_TEMPLATES;
    public static final ForgeConfigSpec.BooleanValue DECISION_SERVICE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> DECISION_SERVICE_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> DECISION_JEV_URL, DECISION_JEV_MODEL, DECISION_JEV_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> DECISION_PERPLEXITY_URL, DECISION_PERPLEXITY_MODEL, DECISION_PERPLEXITY_KEY;
    public static final ForgeConfigSpec.IntValue DECISION_TIMEOUT_MS;
    public static final ForgeConfigSpec.DoubleValue DECISION_MIN_CONFIDENCE;
    public static final ForgeConfigSpec.BooleanValue TTS_SENTENCE_STREAMING;
    public static final ForgeConfigSpec.BooleanValue TTS_EMOTION_CONTROL;
    public static final ForgeConfigSpec.BooleanValue TTS_EMOTION_IN_TEXT;
    public static final ForgeConfigSpec.BooleanValue STEPFUN_TTS_STREAMING;
    public static final ForgeConfigSpec.BooleanValue LLM_STREAMING;
    public static final ForgeConfigSpec.IntValue LLM_OUTPUT_BUDGET_TOKENS;
    public static final ForgeConfigSpec.IntValue LLM_MAX_TOOL_ROUNDS;
    public static final ForgeConfigSpec.IntValue LLM_MAX_REPEAT_TOOL_BATCHES;
    public static final ForgeConfigSpec.IntValue LLM_REQUEST_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue CONTEXT_INPUT_BUDGET_TOKENS;
    public static final ForgeConfigSpec.IntValue MEMORY_COMPRESSION_TARGET_TOKENS;
    public static final ForgeConfigSpec.IntValue MEMORY_RECENT_TURNS;
    public static final ForgeConfigSpec.BooleanValue BACKGROUND_MEMORY_EXTRACTION;
    public static final ForgeConfigSpec.ConfigValue<String> STT_SELECTED_SITE;
    public static final ForgeConfigSpec.BooleanValue FREE_CHAT;
    public static final ForgeConfigSpec.IntValue FREE_CHAT_THRESHOLD;
    public static final ForgeConfigSpec.IntValue FREE_CHAT_SILENCE_MILLIS;
    public static final ForgeConfigSpec.IntValue FREE_CHAT_MIN_SPEECH_MILLIS;
    public static final ForgeConfigSpec.IntValue FREE_CHAT_MAX_UTTERANCE_MILLIS;
    public static final ForgeConfigSpec.IntValue FREE_CHAT_PREROLL_MILLIS;
    public static final ForgeConfigSpec.ConfigValue<String> FREE_CHAT_BARGE_IN;
    public static final ForgeConfigSpec.ConfigValue<String> FREE_CHAT_NOTICE;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_MIN_CONFIDENCE;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_SIGHT_MIN_CONFIDENCE;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_QUESTION_FOLLOW_UP_MIN_CONFIDENCE;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_PLAYER_RELATED_CONFIDENCE;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_PLAYER_OFF_GAZE_RELATED_GAIN;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_OTHER_RELATED_CONFIDENCE;
    public static final ForgeConfigSpec.DoubleValue FREE_CHAT_INTERVENTION_IGNORE_PROBABILITY;
    public static final ForgeConfigSpec.BooleanValue VISION_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> VISION_SELECTED_SITE;
    public static final ForgeConfigSpec.IntValue VISION_SNAPSHOT_MEMORY_MIB;
    public static final ForgeConfigSpec.BooleanValue SHALLOW_SCAN_ENABLED;
    public static final ForgeConfigSpec.IntValue GUI_MAX_WAIT_SECONDS;
    public static final ForgeConfigSpec.DoubleValue BLOCK_INTERACTION_REACH;
    public static final ForgeConfigSpec.BooleanValue COMBAT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue COMBAT_DEFEND_IN_ALL_TASKS;
    public static final ForgeConfigSpec.BooleanValue COMBAT_PROTECT_OWNER;
    public static final ForgeConfigSpec.BooleanValue COMBAT_ATTACK_PLAYERS;
    public static final ForgeConfigSpec.IntValue COMBAT_OWNER_PROTECT_RADIUS;
    public static final ForgeConfigSpec.IntValue COMBAT_ENGAGE_RADIUS;
    public static final ForgeConfigSpec.IntValue COMBAT_THREAT_MEMORY_TICKS;
    public static final ForgeConfigSpec.IntValue COMBAT_COMMAND_TICKS;
    public static final ForgeConfigSpec.IntValue COMBAT_AGGRO_CLEAR_TICKS;
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLEE_CREEPERS;
    public static final ForgeConfigSpec.BooleanValue COMBAT_DEBUG;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("agentRuntimeV1");
        AGENT_RUNTIME = builder.comment("Experimental independent task runtime. Resume saved tasks explicitly after reload.").define("enabled", false);
        AGENT_DIAGNOSTICS = builder.comment("Log stage timings and sizes, never arguments or dialogue.").define("diagnostics", false);
        AGENT_TASK_ANNOUNCEMENTS = builder
                .comment("How task-queue events are announced to the owner:",
                        "detailed = deterministic full queue position, completion and failure text (legacy behaviour);",
                        "brief = the model phrases one short natural line from the real dispatch state (starting now /",
                        "waiting for earlier work / paused / amendment) and its persona; completion/failure lines are",
                        "also phrased by one small background request (with the maid persona and recent chat context)",
                        "and spoken through the maid's TTS site when TTS is enabled; a fixed fallback is used on failure;",
                        "silent = no task notification at all (the model still answers the owner's instruction naturally).",
                        "Whatever this is set to, complete task details (goal, status, queue position, dispatch, outcome)",
                        "are always written to the server log, and asking the maid why a task paused or",
                        "failed always returns the recorded reason.")
                .define("taskAnnouncements", "brief",
                        value -> value instanceof String text && java.util.Set.of("detailed", "brief", "silent").contains(text));
        AGENT_AUTOMATIC_TOOL_SELECTION = builder.comment("Automatically narrow repeated native container work after two completed targets and at least four outstanding targets. Does not change requirements or authorize actions.").define("automaticToolSelection", true);
        AGENT_AMEND_AUTO_RESUME = builder
                .comment("When the owner amends a task that the runtime paused because it failed, got stuck or",
                        "could not verify completion, automatically resume the queue instead of requiring a",
                        "separate explicit 'continue'. An explicit owner pause is preserved and still requires",
                        "continue. Disable to keep every amendment pending until the owner resumes manually.")
                .define("amendAutoResume", true);
        AGENT_CONTEXT_INPUT_BUDGET_TOKENS = builder
                .comment("Input-context budget for the background task executor only; ordinary chat keeps",
                        "[llm].contextInputBudgetTokens. Completed tool batches are compacted at 80% and trimmed",
                        "towards 60% of this value. Defaults to 475000 to retain more task history before compression.",
                        "Lower values reduce per-round replay cost; too low may pause long tasks with",
                        "context_budget_exceeded_after_compaction. Match this budget to the execution model's",
                        "actual context capacity, leaving room for output; model capacity is not detected automatically.")
                .defineInRange("executionContextBudgetTokens", 475000, 4096, 2_097_152);
        AGENT_DEEPSEEK_EFFORT = builder.comment("DeepSeek official endpoint background execution effort: low/high/max, or provider to keep the provider default. Ordinary chat is unchanged.")
                .define("deepseekExecutionEffort", "low", v -> v instanceof String s && java.util.Set.of("low","high","max","provider").contains(s));
        AGENT_DECISION_MODEL = builder.comment("Background EXECUTION LLM as [siteId,modelId]; legacy key kept for compatibility. This is not the typed decision service.",
                "Empty uses the maid's chat model. Uses existing LLM connections; requires function calling.",
                "Invalid selections pause the task instead of silently switching providers. Applies to new execution generations.")
                .define("decisionModel", "", value -> value instanceof String text && text.length() <= 1024);
        builder.push("decisionService");
        DECISION_SERVICE_ENABLED=builder.comment("Use a native typed decision service for grounded finite choices; uncertainty falls back to the execution LLM.").define("enabled",false);
        DECISION_STARTUP_TEMPLATES=builder.comment("Optional speculative startup template/skill lookup. Disabled by default so arbitrary tasks go directly to the execution LLM without a serial decision probe. Committed transfer phases and explicit action plans still use native decisions.").define("startupTemplates",false);
        DECISION_SERVICE_PROVIDER=builder.define("provider","jev",v->v instanceof String s && java.util.Set.of("jev","perplexity").contains(s));
        DECISION_TIMEOUT_MS=builder.defineInRange("timeoutMs",3000,100,30000);
        DECISION_MIN_CONFIDENCE=builder.defineInRange("minConfidence",0.9,0.51,1.0);
        builder.push("jev");
        DECISION_JEV_URL=builder.define("endpoint","https://api.typesafe.ai/v1/systemone");
        DECISION_JEV_MODEL=builder.define("model","jev-1.13.0");
        DECISION_JEV_KEY=builder.comment("Server-only secret; never synchronized back to a client.").define("apiKey","");
        builder.pop();builder.push("perplexity");
        DECISION_PERPLEXITY_URL=builder.define("endpoint","https://api.perplexity.ai/v1/decisions");
        DECISION_PERPLEXITY_MODEL=builder.define("model","pplx-decider-v1.1-27b");
        DECISION_PERPLEXITY_KEY=builder.comment("Server-only secret; never synchronized back to a client.").define("apiKey","");
        builder.pop();builder.pop();
        builder.pop();
        builder.push("tts");
        TTS_SENTENCE_STREAMING = builder
                .comment("Split TTS text by sentence and play each synthesized chunk immediately.")
                .define("sentenceStreaming", true);
        TTS_EMOTION_CONTROL = builder
                .comment("Enable emotion/style markers in TTS via English parentheses (开心)(笑).",
                        "Only takes effect when the TTS model supports it (stepaudio-2.5-tts or mimo-v2.5-tts).",
                        "Use （Chinese parentheses） for action descriptions that should be read as text.")
                .define("emotionControl", false);
        TTS_EMOTION_IN_TEXT = builder
                .comment("When emotion control is on, also show the emotion () brackets in Part 1 (the chat",
                        "bubble text), not only use them invisibly for TTS delivery.")
                .define("emotionInText", false);
        STEPFUN_TTS_STREAMING = builder
                .comment("Use StepFun's official WebSocket streaming synthesis for the stepfun/stepfun_plan TTS",
                        "sites: PCM audio is played while it is generated instead of waiting for one complete",
                        "MP3 response. Chat replies streamed from the LLM are spoken sentence by sentence in",
                        "one synthesis session, so the maid can start talking before the reply is finished.",
                        "When streaming is unavailable or fails before any audio plays, the normal HTTP",
                        "/v1/audio/speech request is used as a fallback.")
                .define("stepfunStreaming", true);
        builder.pop();

        builder.push("llm");
        LLM_STREAMING = builder
                .comment("Use streaming (SSE) output for LLM providers. Tool/agent calls still work; ",
                        "completed sentences are forwarded to TTS as soon as they arrive.")
                .define("streaming", true);
        CONTEXT_INPUT_BUDGET_TOKENS = builder
                .comment("Target input-context budget for AIFun's layered memory planner.",
                        "Large-context models (1M class) should keep this near 475000 so verbatim history is",
                        "only compressed when the budget is actually under pressure instead of after a fixed",
                        "number of turns. Compression starts at 70% (judged, task/budget dependent) and is",
                        "forced at 90%; see CompressionPolicy.")
                .defineInRange("contextInputBudgetTokens", 475000, 8192, 2_097_152);
        MEMORY_COMPRESSION_TARGET_TOKENS = builder
                .comment("Maximum estimated tokens of memory content kept visible immediately after one",
                        "compression pass. Rich game notes are allowed, but the post-compression context",
                        "must stay under this so the next turns start cheap again.")
                .defineInRange("compressionTargetTokens", 15000, 2048, 262144);
        LLM_OUTPUT_BUDGET_TOKENS = builder
                .comment("Maximum generated tokens per request, including reasoning and answer. Provider/model limits still apply.",
                        "ChatGPT subscription controls its own output limit and does not accept this API parameter.")
                .defineInRange("outputBudgetTokens", 65536, 1024, 262144);
        LLM_MAX_TOOL_ROUNDS = builder
                .comment("Maximum tool batches per user request. A batch can contain multiple tools.")
                .defineInRange("maxToolRounds", 256, 1, 4096);
        LLM_MAX_REPEAT_TOOL_BATCHES = builder
                .comment("Maximum consecutive identical tool batches without changed results. Volatile timestamps do not count as progress.")
                .defineInRange("maxRepeatToolBatches", 8, 2, 256);
        LLM_REQUEST_TIMEOUT_SECONDS = builder
                .comment("Timeout for each model request, including consumption of the streamed body; not a total task deadline.")
                .defineInRange("requestTimeoutSeconds", 600, 10, 3600);
        MEMORY_RECENT_TURNS = builder
                .comment("Completed turns kept verbatim after a compression pass (not a compression trigger;",
                        "compression is driven by the context budget and CompressionPolicy).")
                .defineInRange("recentTurns", 8, 2, 64);
        BACKGROUND_MEMORY_EXTRACTION = builder
                .comment("Extract durable facts and older episodes asynchronously after long conversations.")
                .define("backgroundMemoryExtraction", true);
        builder.pop();

        builder.push("stt");
        STT_SELECTED_SITE = builder
                .comment("Selected enabled STT site id. Empty means the first enabled site.")
                .define("selectedSite", "");
        FREE_CHAT = builder
                .comment("Hands-free ambient voice. The client captures microphone audio locally, opens one ASR",
                        "request per volume-gated utterance, then the server-side JEV/Perplexity decision service",
                        "filters invalid or non-addressed speech before starting a normal maid chat turn.",
                        "Requires an enabled native decision service and a buffered-transcription STT site",
                        "(StepFun / Step Plan, MiMo, Qwen). The switch is in the STT configuration screen.",
                        "Capture intentionally continues while maid TTS is playing; a higher onset threshold and",
                        "the server-side self-echo guard keep her own voice from starting a reply loop.")
                .define("freeChat", false);
        FREE_CHAT_THRESHOLD = builder
                .comment("Onset RMS threshold for the free-chat gate (16-bit sample amplitude).",
                        "Raise it if ambient noise triggers speech; lower it if quiet speech is missed.",
                        "The STT configuration screen has a live meter and draggable threshold.")
                .defineInRange("freeChatThreshold", 700, 50, 20000);
        FREE_CHAT_SILENCE_MILLIS = builder
                .comment("Silence duration that ends one utterance.")
                .defineInRange("freeChatSilenceMillis", 700, 200, 5000);
        FREE_CHAT_MIN_SPEECH_MILLIS = builder
                .comment("Utterances shorter than this are discarded before ASR. This only removes energy noise;",
                        "the decision service still judges very short but clear commands.")
                .defineInRange("freeChatMinSpeechMillis", 250, 100, 5000);
        FREE_CHAT_MAX_UTTERANCE_MILLIS = builder
                .comment("Maximum length of one utterance; longer speech is split into consecutive segments.")
                .defineInRange("freeChatMaxUtteranceMillis", 15000, 2000, 30000);
        FREE_CHAT_PREROLL_MILLIS = builder
                .comment("Audio kept before the detected onset so the first syllable is not cut off.")
                .defineInRange("freeChatPrerollMillis", 300, 0, 1000);
        FREE_CHAT_BARGE_IN = builder
                .comment("How free chat interrupts a maid that is currently speaking:",
                        "immediate = stop her the moment the volume gate reports a speech onset; the utterance",
                        "is judged afterwards, so she is also interrupted when it turns out to be invalid",
                        "(headphones recommended: with speakers her own voice may trip the gate);",
                        "after_asr = wait for ASR and the server-side echo check, then stop her TTS;",
                        "off = do not stop early; an accepted new chat turn takes over normally.")
                .define("freeChatBargeIn", "immediate",
                        value -> value instanceof String text && java.util.Set.of("off", "after_asr", "immediate").contains(text));
        FREE_CHAT_NOTICE = builder
                .comment("Where free-chat feedback (recognized text and decision reason codes) is shown:",
                        "hud = a compact fading notice centered just above the action bar (default); it is",
                        "drawn by this mod and never touches the vanilla action bar, so other mods' messages",
                        "are not overwritten;",
                        "chat = the chat box; action_bar = the vanilla action bar (old behaviour, may",
                        "overwrite other action bar messages); off = show no feedback.")
                .define("freeChatNotice", "hud",
                        value -> value instanceof String text && java.util.Set.of("hud", "chat", "action_bar", "off").contains(text));
        FREE_CHAT_MIN_CONFIDENCE = builder
                .comment("Confidence gate for explicitly identifying the addressee target (maid_i, other_player,",
                        "other_entity, environment). Kept separate from the task decision service threshold so",
                        "conversational recall does not weaken task action safety. The validity question is not",
                        "gated here: its two negative branches are the explicit invalid-speech filter.")
                .defineInRange("freeChatMinConfidence", 0.7, 0.5, 1.0);
        FREE_CHAT_SIGHT_MIN_CONFIDENCE = builder
                .comment("Lower target-confidence threshold used when the addressed maid is in the owner's",
                        "view (55 degree cone) or the owner is looking directly at her (bounding-box ray +",
                        "line of sight). This is deterministic contextual evidence, not a second model guess;",
                        "it prevents a hedged but correct maid target from being rejected merely because the",
                        "owner faced a block or GUI. Off-screen or behind-the-back targets still need",
                        "freeChatMinConfidence. Values above freeChatMinConfidence never make the gate stricter.")
                .defineInRange("freeChatSightMinConfidence", 0.5, 0.3, 1.0);
        FREE_CHAT_QUESTION_FOLLOW_UP_MIN_CONFIDENCE = builder
                .comment("Lowest target-confidence threshold used when the addressed maid's latest reply",
                        "contains a question mark and she is in view. A short answer to her question is very",
                        "likely addressed to her even when the decision service hedges. Only applies to a",
                        "maid target; ambient and other addressees keep their normal thresholds.")
                .defineInRange("freeChatQuestionFollowUpMinConfidence", 0.35, 0.2, 1.0);
        FREE_CHAT_PLAYER_RELATED_CONFIDENCE = builder
                .comment("When the player is talking to another player, the decision service must assign at least",
                        "this much probability mass to 'direct' + 'relevant' before the maid may interject.",
                        "This is probability mass, not the calibrated confidence field. Higher than the",
                        "other-entity threshold because player-to-player chat is statistically less likely to",
                        "concern the maid.")
                .defineInRange("freeChatPlayerRelatedConfidence", 0.85, 0.5, 1.0);
        FREE_CHAT_PLAYER_OFF_GAZE_RELATED_GAIN = builder
                .comment("Multiplicative gain applied to the 'direct' + 'relevant' probability mass when the",
                        "decision service says the player is talking to another player, but the owner is NOT",
                        "looking at that player at capture time (server checks a 10 degree view cone, line of",
                        "sight and 16 blocks). Off-gaze, a player-to-player addressee is less certain, so the",
                        "conversation is more likely to concern the maid; looking at the maid or elsewhere",
                        "therefore gets a modest boost. 1.0 disables the boost; values below 1.0 are ignored.")
                .defineInRange("freeChatPlayerOffGazeRelatedGain", 1.15, 1.0, 2.0);
        FREE_CHAT_OTHER_RELATED_CONFIDENCE = builder
                .comment("'direct' + 'relevant' probability mass required when the player is talking to another",
                        "entity, a block or the environment before the maid may interject.")
                .defineInRange("freeChatOtherRelatedConfidence", 0.7, 0.5, 1.0);
        FREE_CHAT_INTERVENTION_IGNORE_PROBABILITY = builder
                .comment("Largest probability the decision service may give to 'ignore' and still let the maid",
                        "intervene. The intervention question is a soft four-way choice: its calibrated",
                        "confidence stays around 0.35-0.55 even for a clear direct command, so it is judged by",
                        "this probability instead of the target confidence gate.")
                .defineInRange("freeChatInterventionIgnoreProbability", 0.5, 0.05, 0.95);
        builder.pop();

        builder.push("vision");
        VISION_ENABLED = builder
                .comment("Allow the observe_surroundings image tool to send maid screenshots to an enabled provider.")
                .define("enabled", false);
        VISION_SELECTED_SITE = builder
                .comment("Independent vision model reference as [siteId,modelId]. Empty disables independent inference; invalid references never switch providers.")
                .define("selectedSite", "");
        VISION_SNAPSHOT_MEMORY_MIB = builder
                .comment("Global compressed screenshot cache budget in MiB. Images stay in memory only; each maid retains at most 10 captures for one hour.")
                .defineInRange("snapshotMemoryMiB", 128, 1, 1024);
        SHALLOW_SCAN_ENABLED = builder
                .comment("Enable the server-only scan_surroundings grounding tool.")
                .define("shallowScanEnabled", true);
        builder.pop();
        builder.push("guiAutomation");
        BLOCK_INTERACTION_REACH = builder.comment("Maximum maid eye-to-clickable-surface distance in blocks for GUI and edible-block actions.",
                        "Walking and interaction share this limit and require an unobstructed block hit.")
                .defineInRange("blockInteractionReach", 3.0, 1.0, 6.0);
        GUI_MAX_WAIT_SECONDS = builder.comment("Maximum cumulative UNTIL_GOAL processing wait per user task, in simulation seconds.")
                .defineInRange("maxWaitSeconds", 900, 1, 86400);
        builder.pop();
        builder.push("combat");
        COMBAT_ENABLED = builder.comment("Server-side combat reflex for maids: retaliate immediately when the maid or its owner",
                        "is hurt, instead of waiting for the 20-tick target sensor. This does not replace the base",
                        "attack work mode. Enabled by default; disable to restore vanilla TLM behavior.")
                .define("enabled", true);
        COMBAT_DEFEND_IN_ALL_TASKS = builder.comment("Allow automatic self-defence/owner protection while the maid is NOT",
                        "in an attack work mode (farm/follow/idle/...). The maid briefly fights, then returns to",
                        "her current work task. Off by default so ordinary work modes stay unchanged.")
                .define("defendInAllTasks", false);
        COMBAT_PROTECT_OWNER = builder.comment("When the owner is hurt nearby, attack the owner's attacker.",
                        "Only the actual owner is protected; public-access guests are not.")
                .define("protectOwner", true);
        COMBAT_ATTACK_PLAYERS = builder.comment("Allow retaliation against players. The owner is NEVER a valid target.",
                        "Player retaliation is additionally refused whenever the maid is a public maid or",
                        "friendly fire is disabled; those two checks cannot be overridden by this option.",
                        "Default true, matching 'players may be fought except the owner'.")
                .define("attackPlayers", true);
        COMBAT_OWNER_PROTECT_RADIUS = builder.comment("Maximum distance from the owner to a maid that may react to owner damage.")
                .defineInRange("ownerProtectRadius", 16, 0, 64);
        COMBAT_ENGAGE_RADIUS = builder.comment("Maximum distance from the maid to a reflex/commanded target.")
                .defineInRange("engageRadius", 16, 4, 48);
        COMBAT_THREAT_MEMORY_TICKS = builder.comment("How long an automatic owner/self threat remains active without new damage.")
                .defineInRange("threatMemoryTicks", 200, 20, 1200);
        COMBAT_COMMAND_TICKS = builder.comment("How long an explicitly commanded attack target remains active without a new command.")
                .defineInRange("commandTicks", 600, 20, 12000);
        COMBAT_AGGRO_CLEAR_TICKS = builder.comment("How long a target cleared through combat_target is suppressed: the base attack task",
                        "will not select it and the maid deals no melee damage to it. A new command re-enables it.")
                .defineInRange("aggroClearTicks", 200, 20, 1200);
        COMBAT_FLEE_CREEPERS = builder.comment("Do not auto-reflex against creepers outside attack work modes (let the base panic/flee",
                        "behavior handle them). Attack work modes keep the base creeper rules. Explicit commands still work.")
                .define("fleeCreepers", true);
        COMBAT_DEBUG = builder.comment("Log reflex decisions at DEBUG level (no chat text, only entity ids and reasons).")
                .define("debugLog", false);
        builder.pop();
        SPEC = builder.build();
    }

    private TouhouAIFunConfig() {
    }

    public static void setDecisionModel(String modelRef) {
        AGENT_DECISION_MODEL.set(modelRef == null ? "" : modelRef);
        AGENT_DECISION_MODEL.save();
    }

    public static void setSentenceStreaming(boolean enabled) {
        TTS_SENTENCE_STREAMING.set(enabled);
        TTS_SENTENCE_STREAMING.save();
    }

    public static void setEmotionControl(boolean enabled) {
        TTS_EMOTION_CONTROL.set(enabled);
        TTS_EMOTION_CONTROL.save();
    }

    public static void setEmotionInText(boolean enabled) {
        TTS_EMOTION_IN_TEXT.set(enabled);
        TTS_EMOTION_IN_TEXT.save();
    }

    public static void setLlmStreaming(boolean enabled) {
        LLM_STREAMING.set(enabled);
        LLM_STREAMING.save();
    }

    public static void setContextInputBudgetTokens(int tokens) {
        CONTEXT_INPUT_BUDGET_TOKENS.set(tokens);
        CONTEXT_INPUT_BUDGET_TOKENS.save();
    }

    public static void setSelectedSttSite(String siteId) {
        STT_SELECTED_SITE.set(siteId);
        STT_SELECTED_SITE.save();
    }

    public static void setFreeChat(boolean enabled) {
        FREE_CHAT.set(enabled);
        FREE_CHAT.save();
    }

    public static void setFreeChatThreshold(int value) {
        int clamped = Math.max(50, Math.min(20000, value));
        FREE_CHAT_THRESHOLD.set(clamped);
        FREE_CHAT_THRESHOLD.save();
    }

    public static String freeChatBargeIn() {
        String value = FREE_CHAT_BARGE_IN.get();
        return value == null ? "immediate" : value;
    }

    public static String freeChatNotice() {
        String value = FREE_CHAT_NOTICE.get();
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "hud", "chat", "action_bar", "off" -> normalized;
            default -> "hud";
        };
    }

    public static void setVisionEnabled(boolean enabled) {
        VISION_ENABLED.set(enabled);
        VISION_ENABLED.save();
    }

    public static void setSelectedVisionSite(String siteId) {
        VISION_SELECTED_SITE.set(siteId == null ? "" : siteId);
        VISION_SELECTED_SITE.save();
    }

    public static void setShallowScanEnabled(boolean enabled) {
        SHALLOW_SCAN_ENABLED.set(enabled);
        SHALLOW_SCAN_ENABLED.save();
    }
}
