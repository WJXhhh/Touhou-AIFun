package com.wjx.touhou_aifun.compat.ai.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Provider registry for web search. The tool depends only on this seam; concrete HTTP/API
 * details stay inside providers. With no explicit preference yet, exactly one provider must be
 * available so selection never silently depends on registration order.
 */
public final class WebSearchRuntime {
    private static final int DEFAULT_MAX_RESULTS = 8;
    private static final Map<String, WebSearchProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new DeepSeekNativeWebSearchProvider());
    }

    private WebSearchRuntime() {
    }

    public static synchronized void register(WebSearchProvider provider) {
        if (PROVIDERS.putIfAbsent(provider.id(), provider) != null) {
            throw new IllegalArgumentException("Duplicate web-search provider: " + provider.id());
        }
    }

    public static synchronized boolean available() {
        return PROVIDERS.values().stream().anyMatch(WebSearchProvider::available);
    }

    public static CompletableFuture<WebSearchResult> search(String query) {
        WebSearchProvider provider;
        synchronized (WebSearchRuntime.class) {
            List<WebSearchProvider> available = new ArrayList<>();
            for (WebSearchProvider candidate : PROVIDERS.values()) {
                if (candidate.available()) {
                    available.add(candidate);
                }
            }
            if (available.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No configured web-search provider is available"));
            }
            if (available.size() > 1) {
                String ids = available.stream().map(WebSearchProvider::id).toList().toString();
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Multiple web-search providers are available: " + ids));
            }
            provider = available.get(0);
        }
        return provider.search(query, DEFAULT_MAX_RESULTS)
                .thenApply(WebSearchRuntime::bounded);
    }

    private static WebSearchResult bounded(WebSearchResult result) {
        if (result.sources().size() <= DEFAULT_MAX_RESULTS) {
            return result;
        }
        return new WebSearchResult(result.content(),
                result.sources().subList(0, DEFAULT_MAX_RESULTS), true);
    }
}
