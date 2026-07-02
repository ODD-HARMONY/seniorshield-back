package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.model.ExtractResult;
import com.seniorshield.model.ImageResult;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.UrlValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** §6.2: POST /api/image */
@WebServlet("/api/image")
public class ImageServlet extends HttpServlet {

    private final ExtractorClient extractorClient = new ExtractorClient();
    private final AnalyzerClient  analyzerClient  = new AnalyzerClient();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));

            List<String> frames;
            if (json.has("frames_base64")) {
                // form (b)
                frames = new ArrayList<>();
                json.get("frames_base64").forEach(n -> frames.add(n.asText()));
            } else {
                // form (a)
                String url = UrlValidator.normalize(json.path("url").asText(""));
                int fc = json.path("frame_count").asInt(3);
                ExtractResult ex = extractorClient.extract(url, fc, "ko");
                frames = (ex.frames != null && ex.frames.framesBase64 != null)
                        ? ex.frames.framesBase64 : List.of();
            }

            String lang = json.path("lang").asText("ko");
            ImageResult result = analyzerClient.image(frames, lang);
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(result));
        } catch (UrlValidator.InvalidUrlException e) {
            resp.setStatus(400);
            resp.getWriter().print("{\"error\":\"invalid_url\",\"detail\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            resp.setStatus(502);
            resp.getWriter().print("{\"error\":\"analyzer_failure\",\"detail\":\"" + e.getMessage() + "\"}");
        }
    }
}
