package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seniorshield.cache.CacheDao;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.client.FactCheckClient;
import com.seniorshield.util.JsonUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** §6.2: GET /api/health — 5개 컴포넌트 상태 반환 */
@WebServlet("/api/health")
public class HealthServlet extends HttpServlet {

    private final ExtractorClient extractorClient = new ExtractorClient();
    private final AnalyzerClient  analyzerClient  = new AnalyzerClient();
    private final FactCheckClient factCheckClient = new FactCheckClient();
    private final CacheDao        cacheDao        = new CacheDao();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");

        ObjectNode node = JsonUtil.MAPPER.createObjectNode();
        node.put("gateway",   "ok");
        node.put("extractor", checkService(() -> extractorClient.healthRaw()));
        node.put("analyzer",  checkService(() -> analyzerClient.healthRaw()));
        node.put("factcheck", checkService(() -> factCheckClient.healthRaw()));
        node.put("db",        cacheDao.ping() ? "ok" : "unavailable");

        resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(node));
    }

    private String checkService(ThrowingSupplier<String> call) {
        try {
            String body = call.get();
            return body != null && body.contains("ok") ? "ok" : "error";
        } catch (Exception e) {
            return "unavailable";
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> { T get() throws Exception; }
}
