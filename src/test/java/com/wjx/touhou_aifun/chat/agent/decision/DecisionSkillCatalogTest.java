package com.wjx.touhou_aifun.chat.agent.decision;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.skill.SkillInstance;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionSkillCatalogTest {
    private static SkillInstance skill(String name,String body){return new SkillInstance(name,"Guide for "+name,Map.of(),body,Map.of("zh_cn.md","本地引用"));}
    @Test void onlyRegisteredAndSchemaAllowedNamesBecomeChoices(){
        var catalog=DecisionSkillCatalog.from(Map.of("actual",skill("actual","do work"),"excluded",skill("excluded","other")),Set.of("actual","invented"));assertEquals(1,catalog.entries().size());assertEquals("actual",catalog.entries().get(0).name());
        var q=catalog.question(new DecisionProvider.Capabilities(128,255,5,false));assertEquals(Set.of("none","s0"),q.getAsJsonObject("criteria").keySet());assertFalse(catalog.modelView().toString().contains("do work"));assertFalse(catalog.modelView().toString().contains("本地引用"));
    }
    @Test void confidenceAndNonePreventSpeculativeSkillLoads(){
        var catalog=DecisionSkillCatalog.from(Map.of("actual",skill("actual","body")),Set.of("actual"));
        assertEquals("actual",catalog.select(JsonParser.parseString("{\"skill\":{\"choice\":\"s0\",\"confidence\":0.99}}").getAsJsonObject(),.9));
        assertNull(catalog.select(JsonParser.parseString("{\"skill\":{\"choice\":\"s0\",\"confidence\":0.5}}").getAsJsonObject(),.9));assertNull(catalog.select(JsonParser.parseString("{\"skill\":{\"choice\":\"none\",\"confidence\":0.99}}").getAsJsonObject(),.9));
    }
    @Test void fullBodyAndLocalizedReferenceChangesInvalidatePendingSelection(){
        var old=DecisionSkillCatalog.from(Map.of("actual",skill("actual","body")),Set.of("actual"));var body=DecisionSkillCatalog.from(Map.of("actual",skill("actual","changed")),Set.of("actual"));assertNotEquals(old.fingerprint(),body.fingerprint());
        var changed=new SkillInstance("actual","Guide for actual",Map.of(),"body",Map.of("zh_cn.md","changed reference"));assertNotEquals(old.fingerprint(),DecisionSkillCatalog.from(Map.of("actual",changed),Set.of("actual")).fingerprint());
        assertEquals(old.fingerprint(),DecisionSkillCatalog.from(Map.of("actual",skill("actual","body")),Set.of("actual")).fingerprint());
    }
    @Test void emptyAndOversizedCataloguesDoNotCutOffRealCandidates(){
        assertNull(new DecisionSkillCatalog.Catalog(List.of()).question(new DecisionProvider.Capabilities(128,255,5,false)));
        var catalog=DecisionSkillCatalog.from(Map.of("a",skill("a","a"),"b",skill("b","b")),Set.of("a","b"));assertNull(catalog.question(new DecisionProvider.Capabilities(128,2,5,false)));assertEquals(2,catalog.entries().size());
    }
}
