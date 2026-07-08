package com.seniorshield.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.client.FactCheckClient;
import com.seniorshield.model.FactCheckResult;
import com.seniorshield.util.JsonUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/** §6.2: POST /api/factcheck */
@WebServlet("/api/factcheck")
public class FactCheckServlet extends HttpServlet {

    private final FactCheckClient client = new FactCheckClient();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(
                    req.getReader().lines().collect(Collectors.joining()));
            String claim    = json.path("claim").asText("");
            String language = json.path("lang").asText("ko");
            FactCheckResult result = client.check(claim, language);
            resp.getWriter().print(JsonUtil.MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            resp.setStatus(502);
            resp.getWriter().print("{\"error\":\"factcheck_failure\",\"detail\":\"" + e.getMessage() + "\"}");
        }
    }
}
