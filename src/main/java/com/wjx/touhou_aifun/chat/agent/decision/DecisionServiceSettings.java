package com.wjx.touhou_aifun.chat.agent.decision;

import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import java.net.URI;
import java.util.Set;

/** Two independent credential profiles. Empty replacement keeps a secret; clearing is explicit. */
public final class DecisionServiceSettings {
    public record View(String provider,boolean enabled,String endpoint,String model,boolean hasKey,int timeoutMs,double minConfidence) { }
    public record Snapshot(View view,String apiKey) {
        public DecisionProvider client() { return new NativeDecisionProvider(view.provider(),view.endpoint(),view.model(),apiKey,view.timeoutMs()); }
        @Override public String toString() { return "DecisionServiceSnapshot["+view.provider()+"]"; }
    }
    private record Profile(ForgeConfigSpec.ConfigValue<String> url,ForgeConfigSpec.ConfigValue<String> model,ForgeConfigSpec.ConfigValue<String> key) { }
    private DecisionServiceSettings() { }
    public static boolean provider(String id) { return Set.of("jev","perplexity").contains(id); }
    private static Profile profile(String id) {
        if(!provider(id)) throw new IllegalArgumentException("invalid_provider");
        return id.equals("jev") ? new Profile(TouhouAIFunConfig.DECISION_JEV_URL,TouhouAIFunConfig.DECISION_JEV_MODEL,TouhouAIFunConfig.DECISION_JEV_KEY)
                :new Profile(TouhouAIFunConfig.DECISION_PERPLEXITY_URL,TouhouAIFunConfig.DECISION_PERPLEXITY_MODEL,TouhouAIFunConfig.DECISION_PERPLEXITY_KEY);
    }
    public static View view(String id) {
        if(!provider(id)) id=TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.get();
        var p=profile(id);
        return new View(id,TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get(),p.url.get(),p.model.get(),!p.key.get().isBlank(),
                TouhouAIFunConfig.DECISION_TIMEOUT_MS.get(),TouhouAIFunConfig.DECISION_MIN_CONFIDENCE.get());
    }
    public static Snapshot snapshot() {
        var v=view("");return new Snapshot(v,profile(v.provider()).key.get());
    }
    public static URI validateEndpoint(String value) {
        try {
            if(value==null || value.length()>1024) throw new IllegalArgumentException();
            URI uri=URI.create(value);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getFragment()!=null || uri.getQuery()!=null) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("invalid_endpoint");}
    }
    public static String effectiveKey(String current,String replacement,boolean clear) {
        if(replacement==null || replacement.length()>512 || replacement.contains("\r") || replacement.contains("\n")) throw new IllegalArgumentException("invalid_key");
        if(clear && !replacement.isBlank()) throw new IllegalArgumentException("key_clear_conflict");
        return clear?"":replacement.isBlank()?current:replacement.trim();
    }
    public static void save(View value,String replacement,boolean clear) {
        var p=profile(value.provider());validateEndpoint(value.endpoint());
        if(value.model()==null || value.model().isBlank() || value.model().length()>256) throw new IllegalArgumentException("invalid_model");
        if(value.timeoutMs()<100 || value.timeoutMs()>30000 || !Double.isFinite(value.minConfidence())
                || value.minConfidence()<0.51 || value.minConfidence()>1) throw new IllegalArgumentException("invalid_threshold");
        String key=effectiveKey(p.key.get(),replacement,clear);
        if(value.enabled() && key.isBlank()) throw new IllegalArgumentException("key_required");
        p.url.set(value.endpoint());p.model.set(value.model().trim());p.key.set(key);
        TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.set(value.provider());TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(value.enabled());
        TouhouAIFunConfig.DECISION_TIMEOUT_MS.set(value.timeoutMs());TouhouAIFunConfig.DECISION_MIN_CONFIDENCE.set(value.minConfidence());
        TouhouAIFunConfig.DECISION_SERVICE_ENABLED.save();
    }
}
