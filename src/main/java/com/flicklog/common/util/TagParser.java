package com.flicklog.common.util;

import java.util.Arrays;
import java.util.List;

public final class TagParser {

    private TagParser() {} // static-only utility, never instantiated

    public static List<String> parse(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawTags.split(","))
                .map(String::trim)
                .toList();
    }
}