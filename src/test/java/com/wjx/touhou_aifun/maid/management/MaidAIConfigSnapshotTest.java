package com.wjx.touhou_aifun.maid.management;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaidAIConfigSnapshotTest {
    @Test
    void partialPatchPreservesUnknownOfflineFields() {
        MaidAIConfigSnapshot current = new MaidAIConfigSnapshot(
                "old-llm", "old-model", "old-tts", "old-voice",
                "en_us", "en_us", "owner", "persona");
        MaidAIConfigSnapshot patch = new MaidAIConfigSnapshot(
                "new-llm", "new-model", "", "", "zh_cn", "", "", "");

        MaidAIConfigSnapshot merged = current.merge(patch, MaidAIConfigSnapshot.LLM);

        assertEquals("new-llm", merged.llmSite());
        assertEquals("new-model", merged.llmModel());
        assertEquals("old-tts", merged.ttsSite());
        assertEquals("old-voice", merged.ttsModel());
        assertEquals("en_us", merged.ttsLanguage());
        assertEquals("persona", merged.customSetting());
    }

    @Test
    void partialApplyOnlyChangesSelectedFields() {
        MaidAIChatSerializable target = new MaidAIChatSerializable();
        target.llmSite = "keep-llm";
        target.customSetting = "keep-persona";
        target.ttsLanguage = "en_us";
        MaidAIConfigSnapshot patch = new MaidAIConfigSnapshot(
                "", "", "", "", "ja_jp", "", "", "replace-persona");

        patch.applyTo(target, MaidAIConfigSnapshot.TTS_LANGUAGE);

        assertEquals("keep-llm", target.llmSite);
        assertEquals("keep-persona", target.customSetting);
        assertEquals("ja_jp", target.ttsLanguage);
    }
}
