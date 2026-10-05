package com.wjx.touhou_aifun.config;

import net.minecraftforge.common.ForgeConfigSpec;
import java.time.Duration;

/** Read at request time so config reloads also affect subsequent tool rounds. */
public final class LLMRuntimeBudget {
    private LLMRuntimeBudget() {}

    private static int read(ForgeConfigSpec.IntValue value) {
        return TouhouAIFunConfig.SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static int outputTokens() { return read(TouhouAIFunConfig.LLM_OUTPUT_BUDGET_TOKENS); }
    public static int toolRounds() { return read(TouhouAIFunConfig.LLM_MAX_TOOL_ROUNDS); }
    public static int repeatBatches() { return read(TouhouAIFunConfig.LLM_MAX_REPEAT_TOOL_BATCHES); }
    public static Duration timeout() { return Duration.ofSeconds(read(TouhouAIFunConfig.LLM_REQUEST_TIMEOUT_SECONDS)); }
}
