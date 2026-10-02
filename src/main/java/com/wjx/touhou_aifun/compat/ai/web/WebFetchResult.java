package com.wjx.touhou_aifun.compat.ai.web;

import javax.annotation.Nullable;
import java.util.List;

/** Bounded, provider-neutral representation of one fetched public webpage. */
public record WebFetchResult(String requestedUrl, String finalUrl, @Nullable String title,
                             @Nullable String contentType, String content,
                             List<WebFetchLink> links, boolean truncated) {
    public WebFetchResult {
        links = List.copyOf(links);
    }
}
