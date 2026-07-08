package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.model.InfoResult;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.UrlValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/** §6.2: POST /api/info */
@WebServlet("/api/info")
public class InfoServlet extends HttpServlet {

    private final AnalyzerClient analyzerClient = new AnalyzerClient();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));

            String keyClaim  = json.path("key_claim").asText("");
            String category  = json.path("category").asText("other");
            String title     = json.path("title").asText(null);
            String desc      = json.path("description").asText(null);
            String lang      = json.path("lang").asText("ko");
            InfoResult result = analyzerClient.info(keyClaim, category, title, desc, lang);
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
