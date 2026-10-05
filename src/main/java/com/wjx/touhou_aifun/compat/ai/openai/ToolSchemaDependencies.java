package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Addon-owned invalidation API. No change to TLM's public ITool contract is required. */
public final class ToolSchemaDependencies {
    private static final Map<String,Function<EntityMaid,String>> DEPENDENCIES=new ConcurrentHashMap<>();
    private static final AtomicLong REVISION=new AtomicLong();
    private ToolSchemaDependencies() { }
    public static void register(String toolId, Function<EntityMaid,String> version) { DEPENDENCIES.put(toolId,Objects.requireNonNull(version)); invalidate(); }
    public static void unregister(String toolId) { DEPENDENCIES.remove(toolId); invalidate(); }
    public static void invalidate() { REVISION.incrementAndGet(); }
    public static String version(EntityMaid maid) {
        StringBuilder out=new StringBuilder().append(REVISION.get()).append(':');
        new TreeMap<>(DEPENDENCIES).forEach((id,version)-> {
            if (ToolRegister.getTool(id)!=null) {
                String value;
                try { value=String.valueOf(version.apply(maid)); } catch(RuntimeException e) { value="unavailable:"+e.getClass().getSimpleName(); }
                out.append(id.length()).append(':').append(id).append(':').append(value.length()).append(':').append(value).append(';');
            }
        });
        // These enums are defined by registries, not immutable constants.
        com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister.allToolCategories().forEach(c ->
                out.append(c.id()).append(':').append(c.summary()).append(':').append(com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister.getContextKeys(c.id())).append(';'));
        return out.toString();
    }
}
