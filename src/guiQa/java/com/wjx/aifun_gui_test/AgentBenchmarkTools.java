package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.*;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.wjx.touhou_aifun.compat.ai.action.GuiTool;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** QA-only mode enforcement: models cannot silently use batching in the individual/full comparison. */
final class AgentBenchmarkTools implements AutoCloseable {
    private final Set<UUID> full = new HashSet<>();
    private final java.lang.reflect.Field registry;
    private final Map<String,ITool<?>> original;
    @SuppressWarnings("unchecked") AgentBenchmarkTools() throws ReflectiveOperationException {
        registry=ToolRegister.class.getDeclaredField("TOOLS");registry.setAccessible(true);
        original=ToolRegister.getAllTools();var replacement=new LinkedHashMap<>(original);
        for(String id:GuiTool.IDS) {
            var delegate=(ITool<JsonObject>)original.get(id);
            replacement.put(id,new ITool<JsonObject>() {
                @Override public String id() {return delegate.id();}
                @Override public String summary(EntityMaid maid) {return delegate.summary(maid);}
                @Override public Parameter parameters(ObjectParameter root,EntityMaid maid) {return delegate.parameters(root,maid);}
                @Override public Codec<JsonObject> codec() {return delegate.codec();}
                @Override public boolean trigger(EntityMaid maid,ChatCompletion context) {return !(id.equals("gui_batch") && full.contains(maid.getUUID())) && delegate.trigger(maid,context);}
                private JsonObject project(JsonObject arguments,LLMCallback callback) {var out=arguments.deepCopy();if(full.contains(callback.getMaid().getUUID())) out.addProperty("detail","full");return out;}
                @Override public LLMCallback onCall(String call,JsonObject arguments,LLMCallback callback) {return delegate.onCall(call,project(arguments,callback),callback);}
                @Override public CompletableFuture<LLMCallback> onCallAsync(String call,JsonObject arguments,LLMCallback callback,LLMClient client) {return delegate.onCallAsync(call,project(arguments,callback),callback,client);}
            });
        }
        registry.set(null,Collections.unmodifiableMap(replacement));
    }
    void mode(EntityMaid maid,boolean compact) {if(!compact) full.add(maid.getUUID());}
    @Override public void close() {
        try {registry.set(null,original);full.clear();} catch(IllegalAccessException impossible) {throw new IllegalStateException(impossible);}
    }
}
