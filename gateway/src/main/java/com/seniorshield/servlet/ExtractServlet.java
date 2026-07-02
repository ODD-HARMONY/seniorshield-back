package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.model.ExtractResult;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.UrlValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/** §6.2: POST /api/extract */
@WebServlet("/api/extract")
public class ExtractServlet extends HttpServlet {

    private final ExtractorClient client = new ExtractorClient();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));
            String raw = json.path("url").asText(null);
            if (raw == null) { resp.setStatus(400); resp.getWriter().print("{\"error\":\"missing url\"}"); return; }

            String url = UrlValidator.normalize(raw);
            int fc = json.path("frame_count").asInt(3);
            ExtractResult result = client.extract(url, fc, "ko");
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(result));
        } catch (UrlValidator.InvalidUrlException e) {
            resp.setStatus(400);
            resp.getWriter().print("{\"error\":\"invalid_url\",\"detail\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            resp.setStatus(502);
            resp.getWriter().print("{\"error\":\"extractor_error\",\"detail\":\"" + e.getMessage() + "\"}");
        }
    }
}
