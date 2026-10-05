package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement.QueryGameContextTool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Dynamic categories always read live. Extensions can declare a reliable content revision. */
public final class VersionedGameContextTool extends QueryGameContextTool {
    private static final Map<String,Function<EntityMaid,String>> VERSIONS=new ConcurrentHashMap<>();
    private static final Map<EntityMaid,Map<String,Entry>> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    private record Entry(String version,String body) { }
    public static void registerVersion(String category, Function<EntityMaid,String> version) { VERSIONS.put(category,Objects.requireNonNull(version)); CACHE.clear(); }
    public static void unregisterVersion(String category) { VERSIONS.remove(category); CACHE.clear(); }
    @Override public LLMCallback onCall(String id,String category,LLMCallback callback) {
        if (!AgentExecution.managed(callback)) return super.onCall(id,category,callback);
        long started=System.nanoTime();
        if (GameContextRegister.allToolCategories().stream().noneMatch(c->c.id().equals(category))) return super.onCall(id,category,callback);
        EntityMaid maid=callback.getMaid();
        Function<EntityMaid,String> dependency=VERSIONS.get(category);
        // Position, equipment, effects and nearby entities have no declared version and are never cached.
        String version=dependency==null?null:dependency.apply(maid);
        Entry existing;
        synchronized(CACHE) { existing=CACHE.getOrDefault(maid,Map.of()).get(category); }
        String body;
        boolean reused=version!=null && existing!=null && version.equals(existing.version);
        if (reused) body=existing.body;
        else {
            var lines=GameContextRegister.getContext(category,maid);
            if (lines.isEmpty()) return super.onCall(id,category,callback);
            body=String.join("\n",lines);
            if(version!=null) synchronized(CACHE) {
                var categories=CACHE.computeIfAbsent(maid,m->new LinkedHashMap<>());
                categories.put(category,new Entry(version,body));
                while(categories.size()>64) categories.remove(categories.keySet().iterator().next());
            }
        }
        JsonObject result=new JsonObject(); result.addProperty("category",category); result.addProperty("game_tick",maid.level().getGameTime());
        result.addProperty("content_version",UUID.nameUUIDFromBytes(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        result.addProperty("reused",reused); result.addProperty("context",body);
        AgentTelemetry.stage("game_context:"+category,started,result.toString().length());
        return callback.addToolResult(result.toString(),id);
    }
}
