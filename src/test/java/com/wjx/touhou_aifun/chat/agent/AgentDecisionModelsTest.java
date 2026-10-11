package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;
import com.wjx.touhou_aifun.vision.ModelRef;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDecisionModelsTest {
    private Map<String, LLMSite> saved;
    @BeforeEach void prepare() { saved = new HashMap<>(AvailableSites.LLM_SITES); AvailableSites.LLM_SITES.clear(); }
    @AfterEach void restore() { AvailableSites.LLM_SITES.clear(); AvailableSites.LLM_SITES.putAll(saved); }
    private LLMOpenAISite site() {
        var site = new ReasoningCompatOpenAISite("decision", new net.minecraft.resources.ResourceLocation("test", "icon"),
                "http://localhost/v1/chat/completions", true, "", false, Map.of(), List.of(new LLMOpenAISite.ModelEntry("fast")));
        AvailableSites.LLM_SITES.put("decision", site); return site;
    }
    @Test void emptySelectionUsesChatModelAndExactReferenceUsesDecisionModel() {
        var site = site(); var fallback = AgentDecisionModels.resolve("", site, "chat");
        assertSame(site,fallback.site()); assertEquals("chat",fallback.model());
        var target = AgentDecisionModels.resolve(new ModelRef("decision","fast").encode(),site,"chat");
        assertSame(site,target.site()); assertEquals("fast",target.model());
        assertEquals(1,AgentDecisionModels.options().size());
    }
    @Test void disabledMissingOrMalformedSelectionNeverFallsBack() {
        var site = site(); String ref = new ModelRef("decision","fast").encode();
        site.setEnabled(false); assertFalse(AgentDecisionModels.selectable(ref));
        assertThrows(IllegalArgumentException.class,()->AgentDecisionModels.resolve(ref,site,"chat"));
        site.setEnabled(true); site.removeModel("fast"); assertFalse(AgentDecisionModels.selectable(ref));
        assertFalse(AgentDecisionModels.selectable("fast"));
    }
}
