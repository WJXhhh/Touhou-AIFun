package com.wjx.touhou_aifun.compat.ai.web;

import java.util.concurrent.CompletableFuture;

/** Swappable backend behind the stable model-facing {@code web_search} tool. */
public interface WebSearchProvider {
    String id();

    boolean available();

    CompletableFuture<WebSearchResult> search(String query, int maxResults);
}
