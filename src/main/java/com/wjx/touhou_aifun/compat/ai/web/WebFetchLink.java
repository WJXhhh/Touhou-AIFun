package com.wjx.touhou_aifun.compat.ai.web;

import javax.annotation.Nullable;

/** One navigable public-web link discovered while extracting a fetched page. */
public record WebFetchLink(String url, @Nullable String label) {
}
