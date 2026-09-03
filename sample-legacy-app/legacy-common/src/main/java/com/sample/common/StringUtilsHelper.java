package com.sample.common;

public class StringUtilsHelper {
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
