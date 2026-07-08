package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.model.ClassifyResult;
import com.seniorshield.model.ExtractResult;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.UrlValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/** §6.2: POST /api/classify — (a) url 또는 (b) subtitle_text */
@WebServlet("/api/classify")
public class ClassifyServlet extends HttpServlet {

    private final ExtractorClient extractorClient = new ExtractorClient();
    private final AnalyzerClient  analyzerClient  = new AnalyzerClient();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));

            String subtitleText;
            if (json.has("subtitle_text")) {
                // form (b)
                subtitleText = json.get("subtitle_text").asText();
            } else {
                // form (a)
                String url = UrlValidator.normalize(json.path("url").asText(""));
                ExtractResult ex = extractorClient.extract(url, 0, "ko");
                subtitleText = (ex.subtitle != null && ex.subtitle.available) ? ex.subtitle.text : "";
            }

            String title = json.path("title").asText(null);
            String description = json.path("description").asText(null);
            String lang = json.path("lang").asText("ko");
            ClassifyResult result = analyzerClient.classify(subtitleText, title, description, lang);
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
