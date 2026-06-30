package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seniorshield.cache.VoteDao;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.UrlValidator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/** POST /api/vote — 투표 제출  |  GET /api/vote — 투표 결과 조회 */
@WebServlet("/api/vote")
public class VoteServlet extends HttpServlet {

    private static final java.util.regex.Pattern HEX64 =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Set<String> VALID_TYPES = Set.of("ok", "suspicious");

    private final VoteDao dao = new VoteDao();

    /** 투표 제출: {"url":"...","client_id":"...","vote_type":"ok"|"suspicious"} */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));

            String rawUrl   = json.path("url").asText(null);
            String clientId = json.path("client_id").asText(null);
            String voteType = json.path("vote_type").asText(null);

            if (rawUrl == null || rawUrl.isBlank()) {
                error(resp, 400, "invalid_url", null); return;
            }
            if (clientId == null || !HEX64.matcher(clientId).matches()) {
                error(resp, 400, "missing_client_id", "64자 hex 문자열이어야 합니다"); return;
            }
            if (voteType == null || !VALID_TYPES.contains(voteType)) {
                error(resp, 400, "invalid_vote_type", "ok 또는 suspicious 만 허용됩니다"); return;
            }

            String canonicalUrl = UrlValidator.canonicalize(rawUrl);
            VoteDao.VoteResult result = dao.vote(canonicalUrl, clientId, voteType);

            ObjectNode node = JsonUtil.MAPPER.createObjectNode();
            node.put("ok",               true);
            node.put("url",              canonicalUrl);
            node.put("vote_type",        result.voteType);
            node.put("ok_count",         result.okCount);
            node.put("suspicious_count", result.suspiciousCount);
            node.put("already_voted",    result.alreadyVoted);
            node.put("changed",          result.changed);
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(node));

        } catch (UrlValidator.InvalidUrlException e) {
            error(resp, 400, "invalid_url", e.getMessage());
        } catch (Exception e) {
            error(resp, 500, "internal_error", e.getMessage());
        }
    }

    /** 투표 결과 조회: GET /api/vote?url=... */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            String rawUrl = req.getParameter("url");
            if (rawUrl == null || rawUrl.isBlank()) {
                error(resp, 400, "missing_url_parameter", null); return;
            }

            String canonicalUrl = UrlValidator.canonicalize(rawUrl);
            VoteDao.AggregateEntry entry = dao.getAggregate(canonicalUrl);

            ObjectNode node = JsonUtil.MAPPER.createObjectNode();
            node.put("url",              canonicalUrl);
            node.put("ok_count",         entry != null ? entry.okCount         : 0);
            node.put("suspicious_count", entry != null ? entry.suspiciousCount : 0);
            node.put("last_voted_at",    entry != null ? entry.lastVotedAt     : null);
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(node));

        } catch (UrlValidator.InvalidUrlException e) {
            error(resp, 400, "invalid_url", e.getMessage());
        } catch (Exception e) {
            error(resp, 500, "internal_error", null);
        }
    }

    private void error(HttpServletResponse resp, int status, String code, String detail) throws IOException {
        resp.setStatus(status);
        ObjectNode node = JsonUtil.MAPPER.createObjectNode();
        node.put("error", code);
        if (detail != null) node.put("detail", detail);
        resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(node));
    }
}
