package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionQuestionCompilerTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static ActionPlan.Step step(ActionPlan.Action... actions){return new ActionPlan.Step("Do the owner requested action",List.of(actions),null,null);}
    @Test void compilesToolAndAllClosedParametersTogetherAndReadsOnlyChosenTool() {
        var tools=Map.of("follow",new DecisionQuestionCompiler.Tool("follow","Follow",json("{\"type\":\"object\",\"properties\":{\"follow\":{\"type\":\"boolean\"}},\"required\":[\"follow\"]}")),
                "work",new DecisionQuestionCompiler.Tool("work","Work",json("{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\",\"enum\":[\"idle\",\"farm\"]}},\"required\":[\"task\"]}")));
        var compiled=DecisionQuestionCompiler.compile(step(new ActionPlan.Action("follow",new JsonObject(),"Follow owner"),new ActionPlan.Action("work",new JsonObject(),"Work")),tools,new JsonObject(),DecisionProtocolTest.CAPS);
        assertEquals(3,compiled.questions().size());assertNull(compiled.deterministic());
        var answers=json("{\"action\":{\"choice\":\"a0\",\"confidence\":0.95},\"p0_0\":{\"noul\":0.98},\"p1_0\":{\"confidence\":0.1,\"choice\":\"v1\"}}");
        var selected=compiled.select(answers,.9);assertEquals("follow",selected.tool());assertTrue(selected.arguments().get("follow").getAsBoolean());
        answers.getAsJsonObject("p0_0").addProperty("noul",.5);assertNull(compiled.select(answers,.9));
        answers.getAsJsonObject("p0_0").addProperty("noul",.01);assertFalse(compiled.select(answers,.9).arguments().get("follow").getAsBoolean());
        answers.getAsJsonObject("action").addProperty("choice",DecisionQuestionCompiler.FALLBACK);assertNull(compiled.select(answers,.9));
    }
    @Test void fixedArgumentsAndLiveBindingsRunWithoutAnExtraModelDecision() {
        var tool=new DecisionQuestionCompiler.Tool("close_gui","Close",json("{\"type\":\"object\",\"properties\":{\"session_id\":{\"type\":\"string\"}},\"required\":[\"session_id\"]}"));
        var action=new ActionPlan.Action("close_gui",json("{\"session_id\":\"$live.session_id\"}"),"Close");
        var compiled=DecisionQuestionCompiler.compile(step(action),Map.of("close_gui",tool),json("{\"session_id\":\"current\"}"),DecisionProtocolTest.CAPS);
        assertEquals("current",compiled.deterministic().arguments().get("session_id").getAsString());assertEquals(0,compiled.questions().size());
        assertNull(DecisionQuestionCompiler.compile(step(action),Map.of("close_gui",tool),new JsonObject(),DecisionProtocolTest.CAPS));
    }
    @Test void requiredFreeTextAndImagesFallBackInsteadOfGeneratingFakeFiniteValues() {
        var tool=new DecisionQuestionCompiler.Tool("web_search","Search",json("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));
        assertNull(DecisionQuestionCompiler.compile(step(new ActionPlan.Action("web_search",new JsonObject(),"Search")),Map.of("web_search",tool),new JsonObject(),DecisionProtocolTest.CAPS));
        var explicit=DecisionQuestionCompiler.compile(step(new ActionPlan.Action("web_search",json("{\"query\":\"Minecraft chest\"}"),"Search")),Map.of("web_search",tool),new JsonObject(),DecisionProtocolTest.CAPS);
        assertNotNull(explicit.deterministic());
        assertNull(DecisionQuestionCompiler.compile(step(new ActionPlan.Action("observe_surroundings",new JsonObject(),"Look")),Map.of("observe_surroundings",tool),new JsonObject(),DecisionProtocolTest.CAPS));
    }
    @Test void questionOverflowAndWrongFixedTypesCannotDispatch() {
        var properties=new JsonObject();for(int i=0;i<128;i++) properties.add("p"+i,json("{\"type\":\"boolean\"}"));var schema=new JsonObject();schema.add("properties",properties);
        assertNull(DecisionQuestionCompiler.compile(step(new ActionPlan.Action("many",new JsonObject(),"Many")),Map.of("many",new DecisionQuestionCompiler.Tool("many","Many",schema)),new JsonObject(),DecisionProtocolTest.CAPS));
        assertFalse(DecisionQuestionCompiler.matches(new JsonPrimitive("true"),json("{\"type\":\"boolean\"}")));
        assertFalse(DecisionQuestionCompiler.matches(new JsonPrimitive(1.2),json("{\"type\":\"integer\"}")));
    }
    @Test void retainsUnrepresentableBranchesSoRemainingActionIsNotChosenBySubstitution() {
        var follow=new DecisionQuestionCompiler.Tool("follow","Follow",json("{\"type\":\"object\",\"properties\":{}}"));
        var compiled=DecisionQuestionCompiler.compile(step(new ActionPlan.Action("follow",new JsonObject(),"When owner asks to follow"),new ActionPlan.Action("observe_surroundings",new JsonObject(),"When the goal requires reading a picture")),Map.of("follow",follow),new JsonObject(),DecisionProtocolTest.CAPS);
        assertNull(compiled.deterministic());assertTrue(compiled.questions().getAsJsonObject("action").getAsJsonObject("criteria").get("d1").getAsString().contains("picture"));
        assertNull(compiled.select(json("{\"action\":{\"choice\":\"d1\",\"confidence\":0.99}}"),.9));
    }
}
