package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekTaskRequestPolicyTest {
    @Test void backgroundRequestsUseProviderSpecificEffortWithoutChangingThinking() {
        var openai=new JsonObject();DeepSeekTaskRequestPolicy.apply(openai,"https://api.deepseek.com/v1","deepseek-v4-flash",true,false,"low");
        assertEquals("low",openai.get("reasoning_effort").getAsString());assertFalse(openai.has("thinking"));
        var anthropic=JsonParser.parseString("{\"output_config\":{\"other\":true}}").getAsJsonObject();
        DeepSeekTaskRequestPolicy.apply(anthropic,"https://api.deepseek.com/anthropic","deepseek-v4-pro",true,true,"low");
        assertEquals("low",anthropic.getAsJsonObject("output_config").get("effort").getAsString());assertTrue(anthropic.getAsJsonObject("output_config").get("other").getAsBoolean());assertFalse(anthropic.has("thinking"));
    }
    @Test void ordinaryChatOtherProvidersModelsAndProviderDefaultRemainUntouched() {
        for(var args:new String[][]{{"https://api.deepseek.com","deepseek-v4-flash","false","low"},{"https://api.deepseek.com.other.test","deepseek-v4-flash","true","low"},{"https://other.test","deepseek-v4-flash","true","low"},{"https://api.deepseek.com","deepseek-chat","true","low"},{"https://api.deepseek.com","deepseek-v4-flash","true","provider"}}) {
            var body=new JsonObject();DeepSeekTaskRequestPolicy.apply(body,args[0],args[1],Boolean.parseBoolean(args[2]),true,args[3]);assertEquals(0,body.size());
        }
    }
    @Test void explicitDisabledThinkingAndConfigurableHighOrMaxAreHonored() {
        var disabled=JsonParser.parseString("{\"thinking\":{\"type\":\"disabled\"}}").getAsJsonObject();var original=disabled.deepCopy();
        DeepSeekTaskRequestPolicy.apply(disabled,"https://api.deepseek.com","deepseek-v4-flash",true,false,"low");assertEquals(original,disabled);
        for(String effort:new String[]{"high","max"}) {var body=new JsonObject();DeepSeekTaskRequestPolicy.apply(body,"https://api.deepseek.com","deepseek-v4-pro",true,true,effort);assertEquals(effort,body.getAsJsonObject("output_config").get("effort").getAsString());}
    }
}
