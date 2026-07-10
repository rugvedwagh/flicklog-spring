package com.flicklog.common.util;

public final class SlugParser {

    private SlugParser() {}

    // Mirrors the JS split(/-(?=[^ -]+$)/): a slug like "my-post-title-<mongoId>"
    // has the ID appended after the LAST hyphen, so we split there rather than the first.
    public static String extractId(String slugId) {
        int lastHyphen = slugId.lastIndexOf('-');
        return lastHyphen == -1 ? slugId : slugId.substring(lastHyphen + 1);
    }
}