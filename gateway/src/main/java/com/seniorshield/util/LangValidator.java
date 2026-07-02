package com.seniorshield.util;

import java.util.Locale;
import java.util.Set;

public class LangValidator {
    private static final Set<String> SUPPORTED = Set.of("ko", "en");
    private static final String      DEFAULT   = "ko";

    /** 지원 언어 외 값은 기본값으로 정규화 (400 에러 대신 방어적 처리) */
    public static String normalize(String lang) {
        if (lang == null) return DEFAULT;
        String l = lang.toLowerCase(Locale.ROOT).trim();
        return SUPPORTED.contains(l) ? l : DEFAULT;
    }
}
