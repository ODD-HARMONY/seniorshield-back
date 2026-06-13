package com.seniorshield.util;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlValidator {
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern SHORTS   = Pattern.compile("/shorts/([A-Za-z0-9_-]{11})");
    private static final Pattern WATCH    = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    private UrlValidator() {}

    public static String normalize(String rawUrl) throws InvalidUrlException {
        if (rawUrl == null || rawUrl.isBlank()) throw new InvalidUrlException("URL is empty");
        try {
            URI uri  = URI.create(rawUrl.trim());
            String h = uri.getHost();
            if (h == null) throw new InvalidUrlException("No host");
            if (!h.equals("youtube.com") && !h.equals("www.youtube.com")
                    && !h.equals("m.youtube.com") && !h.equals("youtu.be"))
                throw new InvalidUrlException("Not a YouTube URL");

            String path  = uri.getPath()  != null ? uri.getPath()  : "";
            String query = uri.getQuery() != null ? uri.getQuery() : "";
            String id    = null;

            if (h.equals("youtu.be")) {
                String[] parts = path.split("/");
                if (parts.length >= 2) id = parts[1];
            }
            if (id == null) { Matcher m = SHORTS.matcher(path);  if (m.find()) id = m.group(1); }
            if (id == null) { Matcher m = WATCH.matcher(query);   if (m.find()) id = m.group(1); }

            if (id == null || !VIDEO_ID.matcher(id).matches())
                throw new InvalidUrlException("Cannot extract video ID");

            return "https://www.youtube.com/watch?v=" + id;
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("Malformed URL: " + e.getMessage());
        }
    }

    public static String extractVideoId(String normalizedUrl) {
        Matcher m = WATCH.matcher(normalizedUrl);
        return m.find() ? m.group(1) : null;
    }

    /** §4: normalize() 의 alias — 캐시 키 및 suspicion_aggregate 키와 동일한 정규화 */
    public static String canonicalize(String rawUrl) throws InvalidUrlException {
        return normalize(rawUrl);
    }

    public static class InvalidUrlException extends Exception {
        public InvalidUrlException(String msg) { super(msg); }
    }
}
