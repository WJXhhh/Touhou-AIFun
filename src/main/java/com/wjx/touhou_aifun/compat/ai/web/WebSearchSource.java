package com.wjx.touhou_aifun.compat.ai.web;

import javax.annotation.Nullable;

/** One normalized, citeable web-search source. */
public record WebSearchSource(String url, @Nullable String title,
                              @Nullable String snippet, @Nullable String publishedAt) {
}
