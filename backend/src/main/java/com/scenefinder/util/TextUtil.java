package com.scenefinder.util;

public final class TextUtil {

    private TextUtil() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
