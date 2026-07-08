package com.seniorshield.client;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.seniorshield.model.AdResult;
import com.seniorshield.model.ClassifyResult;
import com.seniorshield.model.ImageResult;
import com.seniorshield.model.InfoResult;
import com.seniorshield.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class AnalyzerClient {
    private static final String URL;
    private static final long   TIMEOUT_MS;

    static {
        String u = System.getenv("ANALYZER_URL");
        URL = u != null ? u : "http://localhost:8001";
        String t = System.getenv("ANALYZER_TIMEOUT_MS");
        TIMEOUT_MS = t != null ? Long.parseLong(t) : 20_000L;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ClassifyResult classify(String subtitleText, String title, String description, String lang) throws Exception {
        String body = JsonUtil.MAPPER.writeValueAsString(
                JsonUtil.MAPPER.createObjectNode()
                        .put("subtitle_text", subtitleText)
                        .put("title",         title != null ? title : "")
                        .put("description",   description != null ? description : "")
                        .put("lang", lang));
        return post("/classify", body, ClassifyResult.class);
    }

    public InfoResult info(String keyClaim, String category, String title, String description, String lang) throws Exception {
        String body = JsonUtil.MAPPER.writeValueAsString(
                JsonUtil.MAPPER.createObjectNode()
                        .put("key_claim",   keyClaim != null ? keyClaim : "")
                        .put("category",    category)
                        .put("title",       title != null ? title : "")
                        .put("description", description != null ? description : "")
                        .put("lang", lang));
        return post("/info", body, InfoResult.class);
    }

    public ImageResult image(List<String> framesBase64, String lang) throws Exception {
        ArrayNode arr = JsonUtil.MAPPER.createArrayNode();
        framesBase64.forEach(arr::add);
        String body = JsonUtil.MAPPER.writeValueAsString(
                JsonUtil.MAPPER.createObjectNode()
                        .put("lang", lang)
                        .set("frames_base64", arr));
        return post("/image", body, ImageResult.class);
    }

    public AdResult ad(List<String> framesBase64, String subtitleText, String initialLabel, String lang) throws Exception {
        ArrayNode arr = JsonUtil.MAPPER.createArrayNode();
        framesBase64.forEach(arr::add);
        String body = JsonUtil.MAPPER.writeValueAsString(
                JsonUtil.MAPPER.createObjectNode()
                        .put("subtitle_text", subtitleText)
                        .put("initial_label", initialLabel)
                        .put("lang", lang)
                        .set("frames_base64", arr));
        return post("/ad", body, AdResult.class);
    }

    public String healthRaw() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + "/health"))
                .GET().timeout(Duration.ofSeconds(5)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private <T> T post(String path, String body, Class<T> type) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("Analyzer error " + path + ": " + resp.body());
        return JsonUtil.MAPPER.readValue(resp.body(), type);
    }
}
