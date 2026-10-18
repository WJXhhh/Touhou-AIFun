package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import java.net.URI;
import java.util.*;

/** Wire restriction for verified official endpoints; compatible providers use schema filtering only. */
public final class AllowedToolsRequestPolicy {
    private AllowedToolsRequestPolicy() { }
    public static boolean apply(JsonObject body,String endpoint,boolean responses) {
        try {
            URI uri=URI.create(endpoint);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || !"api.openai.com".equalsIgnoreCase(uri.getHost())
                    || body.has("tool_choice") || !body.has("tools"))return false;
            Set<String> names=new TreeSet<>();
            for(var value:body.getAsJsonArray("tools")) {
                var tool=value.getAsJsonObject();
                if(!tool.has("type") || !"function".equals(tool.get("type").getAsString()))return false;
                var function=responses?tool:tool.getAsJsonObject("function");
                if(function==null || !function.has("name"))return false;
                names.add(function.get("name").getAsString());
            }
            if(names.isEmpty())return false;
            body.add("tool_choice",choice(names,responses));return true;
        } catch(RuntimeException invalid) {return false;}
    }
    public static int reserve(Collection<String> names) {
        return Math.max(ContextTokenEstimator.estimate(choice(names,false).toString()),
                ContextTokenEstimator.estimate(choice(names,true).toString()));
    }
    private static JsonObject choice(Collection<String> names,boolean responses) {
        JsonArray tools=new JsonArray();
        for(String name:new TreeSet<>(names)) {
            JsonObject item=new JsonObject();item.addProperty("type","function");
            if(responses)item.addProperty("name",name);
            else {var function=new JsonObject();function.addProperty("name",name);item.add("function",function);}
            tools.add(item);
        }
        JsonObject allowed=new JsonObject();allowed.addProperty("mode","auto");allowed.add("tools",tools);
        JsonObject choice=new JsonObject();choice.addProperty("type","allowed_tools");
        if(responses) {choice.add("mode",allowed.get("mode"));choice.add("tools",tools);}
        else choice.add("allowed_tools",allowed);
        return choice;
    }
}
