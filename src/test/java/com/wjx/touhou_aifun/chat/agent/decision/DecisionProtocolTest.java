package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionProtocolTest {
    static final DecisionProvider.Capabilities CAPS=new DecisionProvider.Capabilities(128,255,10,false);
    static JsonObject questions() {
        var q=new JsonObject();q.add("pick",DecisionProtocol.choice("Pick",Map.of("a","First","b","Second")));
        q.add("yes",DecisionProtocol.noul("Yes?"));q.add("score",JsonParser.parseString("{\"type\":\"score\",\"instructions\":\"Rate\",\"criteria\":[\"low\",\"high\"]}"));return q;
    }
    static JsonObject response() {return JsonParser.parseString("""
            {"model":"pplx-decider-v1.1-27b","answers":{
             "pick":{"type":"choice","choice":"a","confidence":0.93,"probabilities":{"a":0.95,"b":0.05}},
             "yes":{"type":"noul","noul":0.98},
             "score":{"type":"score","score":0.9,"confidence":0.85,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.1,"1":0.9}}
            },"usage":{"input_tokens":123,"output_tokens":3}}
            """).getAsJsonObject();}
    @Test void bothProvidersShareNativeTypesAndOnlyThreeTopLevelFields() {
        for(String model:List.of("jev-1.13.0","pplx-decider-v1.1-27b")) {
            var state=new JsonObject();state.addProperty("goal","test");var body=DecisionProtocol.request(model,state,questions(),CAPS);
            assertEquals(Set.of("model","state","questions"),body.keySet());state.addProperty("goal","changed");
            assertEquals("test",body.getAsJsonObject("state").get("goal").getAsString());
            var data=response();data.addProperty("model",model);var result=DecisionProtocol.response(data.toString(),questions(),"request-id");
            assertEquals(model,result.model());assertEquals(123,result.inputTokens());assertEquals(3,result.outputTokens());
            assertEquals(.93,result.answers().getAsJsonObject("pick").get("confidence").getAsDouble());
        }
    }
    @Test void rejectsMissingQuestionsWrongTypeUnknownChoicesAndMalformedDistributions() {
        for(int i=0;i<7;i++) {
            var data=response();var answers=data.getAsJsonObject("answers");var pick=answers.getAsJsonObject("pick");
            switch(i) {
                case 0 -> answers.remove("yes");case 1 -> pick.addProperty("choice","unknown");
                case 2 -> pick.getAsJsonObject("probabilities").addProperty("a",.7);
                case 3 -> pick.addProperty("type","noul");case 4 -> pick.remove("confidence");
                case 5 -> answers.getAsJsonObject("yes").addProperty("noul",1.1);
                case 6 -> answers.getAsJsonObject("score").addProperty("score",.1);
            }
            assertThrows(IllegalArgumentException.class,()->DecisionProtocol.response(data.toString(),questions(),""));
        }
        assertThrows(IllegalArgumentException.class,()->DecisionProtocol.response("<html>gateway error</html>",questions(),""));
    }
    @Test void enforcesQuestionAndChoiceLimitsBeforeNetworkAndNeverSilentlyTruncates() {
        var q=new JsonObject();for(int i=0;i<129;i++)q.add("q"+i,DecisionProtocol.noul("Is it true?"));
        assertThrows(IllegalArgumentException.class,()->DecisionProtocol.request("model",new JsonObject(),q,CAPS));
        q.remove("q128");assertEquals(128,DecisionProtocol.request("model",new JsonObject(),q,CAPS).getAsJsonObject("questions").size());
        var options=new LinkedHashMap<String,String>();for(int i=0;i<255;i++) options.put("v"+i,"Option "+i);
        var single=new JsonObject();single.add("choice",DecisionProtocol.choice("Pick",options));
        assertNotNull(DecisionProtocol.request("model",new JsonObject(),single,CAPS));options.put("overflow","extra");single.add("choice",DecisionProtocol.choice("Pick",options));
        assertThrows(IllegalArgumentException.class,()->DecisionProtocol.request("model",new JsonObject(),single,CAPS));
    }
    @Test void secretsAreKeptOrExplicitlyClearedAndCannotBeInjectedIntoHeaders() {
        assertEquals("secret",DecisionServiceSettings.effectiveKey("secret","",false));
        assertEquals("",DecisionServiceSettings.effectiveKey("secret","",true));
        assertEquals("new",DecisionServiceSettings.effectiveKey("secret","new",false));
        assertThrows(IllegalArgumentException.class,()->DecisionServiceSettings.effectiveKey("secret","new",true));
        assertThrows(IllegalArgumentException.class,()->DecisionServiceSettings.effectiveKey("secret","bad\nkey",false));
        assertThrows(IllegalArgumentException.class,()->DecisionServiceSettings.validateEndpoint("https://user:secret@example.com/v1/decisions"));
    }
}
