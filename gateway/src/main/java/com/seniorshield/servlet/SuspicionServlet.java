package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seniorshield.cache.SuspicionDao;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.UrlValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/** §2: POST/GET /api/suspicion */
@WebServlet("/api/suspicion")
public class SuspicionServlet extends HttpServlet {

    private static final java.util.regex.Pattern HEX64 =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{64}$");

    private final SuspicionDao dao = new SuspicionDao();

    /** §2.1: "의심돼요" 신고 */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));

            String rawUrl  = json.path("url").asText(null);
            String clientId = json.path("client_id").asText(null);

            if (rawUrl == null || rawUrl.isBlank()) {
                resp.setStatus(400);
                resp.getWriter().print("{\"error\":\"invalid_url\"}");
                return;
            }
            if (clientId == null || !HEX64.matcher(clientId).matches()) {
                resp.setStatus(400);
                resp.getWriter().print("{\"error\":\"missing_client_id\"}");
                return;
            }

            String canonicalUrl = UrlValidator.canonicalize(rawUrl);

            SuspicionDao.ReportResult result = dao.report(canonicalUrl, clientId);

            ObjectNode node = JsonUtil.MAPPER.createObjectNode();
            node.put("ok",               true);
            node.put("url",              canonicalUrl);
            node.put("suspicion_count",  result.suspicionCount);
            node.put("already_reported", result.alreadyReported);
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(node));

        } catch (UrlValidator.InvalidUrlException e) {
            resp.setStatus(400);
            resp.getWriter().print("{\"error\":\"invalid_url\",\"detail\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().print("{\"error\":\"internal_error\",\"detail\":\"" + e.getMessage() + "\"}");
        }
    }

    /** §2.2: 카운트 조회 */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            String rawUrl = req.getParameter("url");
            if (rawUrl == null || rawUrl.isBlank()) {
                resp.setStatus(400);
                resp.getWriter().print("{\"error\":\"missing url parameter\"}");
                return;
            }

            String canonicalUrl = UrlValidator.canonicalize(rawUrl);
            SuspicionDao.AggregateEntry entry = dao.getAggregate(canonicalUrl);

            ObjectNode node = JsonUtil.MAPPER.createObjectNode();
            node.put("url",              canonicalUrl);
            node.put("suspicion_count",  entry != null ? entry.suspicionCount  : 0);
            node.put("last_reported_at", entry != null ? entry.lastReportedAt  : null);
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(node));

        } catch (UrlValidator.InvalidUrlException e) {
            resp.setStatus(400);
            resp.getWriter().print("{\"error\":\"invalid_url\",\"detail\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().print("{\"error\":\"internal_error\"}");
        }
    }
}
