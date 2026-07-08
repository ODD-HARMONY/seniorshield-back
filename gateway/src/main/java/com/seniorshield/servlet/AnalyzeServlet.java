package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.model.AnalyzeResponse;
import com.seniorshield.pipeline.AnalysisPipeline;
import com.seniorshield.pipeline.AnalyzeQueue;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.LangValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/** §6.1: POST /api/analyze — 전체 파이프라인 */
@WebServlet("/api/analyze")
public class AnalyzeServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        String body = req.getReader().lines().collect(Collectors.joining());
        JsonNode json;
        try { json = JsonUtil.MAPPER.readTree(body); }
        catch (Exception e) { resp.setStatus(400); writeError(resp, "invalid_url", "Bad JSON"); return; }

        String url = json.path("url").asText(null);
        if (url == null || url.isBlank()) {
            resp.setStatus(400); writeError(resp, "invalid_url", "Missing url"); return;
        }

        String lang = LangValidator.normalize(json.path("lang").asText(null));

        AnalyzeResponse result;
        try {
            result = AnalyzeQueue.getInstance().submit(
                () -> AnalysisPipeline.getInstance().analyze(url, lang)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.setStatus(503);
            writeError(resp, "service_unavailable", "Request interrupted");
            return;
        } catch (Exception e) {
            resp.setStatus(500);
            writeError(resp, "internal_error", e.getMessage());
            return;
        }
        if (result.error != null) {
            resp.setStatus(result.error.equals("invalid_url") ? 400 : 502);
        }
        resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(result));
    }

    private void writeError(HttpServletResponse resp, String code, String detail) throws IOException {
        resp.getWriter().print("{\"error\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
