package com.wjx.touhou_aifun.compat.ai.web;

import javax.annotation.Nullable;
import java.util.List;

/** Provider-neutral web-search result returned to the model-facing tool. */
public record WebSearchResult(@Nullable String content, List<WebSearchSource> sources, boolean truncated) {
    public WebSearchResult {
        sources = List.copyOf(sources);
    }
}
