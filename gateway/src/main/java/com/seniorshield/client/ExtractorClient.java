package com.seniorshield.client;

import com.seniorshield.model.ExtractResult;
import com.seniorshield.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ExtractorClient {
    private static final String URL;
    private static final long   TIMEOUT_MS;

    static {
        String u = System.getenv("EXTRACTOR_URL");
        URL = u != null ? u : "http://localhost:8000";
        String t = System.getenv("EXTRACTOR_TIMEOUT_MS");
        TIMEOUT_MS = t != null ? Long.parseLong(t) : 30_000L;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ExtractResult extract(String url, int frameCount, String subtitleLang) throws Exception {
        String body = JsonUtil.MAPPER.writeValueAsString(
                JsonUtil.MAPPER.createObjectNode()
                        .put("url", url)
                        .put("frame_count", frameCount)
                        .put("extract_subtitle", true)
                        .put("extract_frames", frameCount > 0)
                        .put("subtitle_lang", subtitleLang)
        );
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + "/extract"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("Extractor error: " + resp.body());
        return JsonUtil.MAPPER.readValue(resp.body(), ExtractResult.class);
    }

    public String healthRaw() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + "/health"))
                .GET().timeout(Duration.ofSeconds(5)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
