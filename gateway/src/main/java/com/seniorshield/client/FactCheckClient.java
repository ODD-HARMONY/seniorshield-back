package com.seniorshield.client;

import com.seniorshield.model.FactCheckResult;
import com.seniorshield.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class FactCheckClient {
    private static final String URL;
    private static final long   TIMEOUT_MS;

    static {
        String u = System.getenv("FACTCHECK_URL");
        URL = u != null ? u : "http://localhost:8002";
        String t = System.getenv("FACTCHECK_TIMEOUT_MS");
        TIMEOUT_MS = t != null ? Long.parseLong(t) : 10_000L;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public FactCheckResult check(String claim, String lang) throws Exception {
        String body = JsonUtil.MAPPER.writeValueAsString(
                JsonUtil.MAPPER.createObjectNode()
                        .put("claim", claim)
                        .put("lang", lang));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + "/factcheck"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("FactCheck error: " + resp.body());
        return JsonUtil.MAPPER.readValue(resp.body(), FactCheckResult.class);
    }

    public String healthRaw() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + "/health"))
                .GET().timeout(Duration.ofSeconds(5)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
