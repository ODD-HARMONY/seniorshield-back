package com.seniorshield.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorshield.util.JsonUtil;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** §5.2: 한 client_id가 1분에 10건 초과 시 429 반환 */
public class RateLimitFilter implements Filter {

    private static final Logger log = Logger.getLogger(RateLimitFilter.class.getName());
    private static final int  LIMIT_PER_MINUTE = 10;
    private static final long WINDOW_MS        = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rate-limit-cleaner");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void init(FilterConfig cfg) {
        cleaner.scheduleAtFixedRate(this::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpReq  = (HttpServletRequest)  request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // GET/비POST는 통과
        if (!"POST".equalsIgnoreCase(httpReq.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 바디를 캐시해 두 번 읽기 허용
        CachedBodyRequest cached = new CachedBodyRequest(httpReq);

        String clientId = extractClientId(cached.body);
        if (clientId != null && isLimited(clientId)) {
            httpResp.setStatus(429);
            httpResp.setHeader("Retry-After", "60");
            httpResp.setContentType("application/json;charset=UTF-8");
            httpResp.getWriter().print("{\"error\":\"rate_limited\"}");
            return;
        }

        chain.doFilter(cached, response);
    }

    @Override
    public void destroy() {
        cleaner.shutdown();
    }

    private boolean isLimited(String clientId) {
        long now      = System.currentTimeMillis();
        long cutoff   = now - WINDOW_MS;
        Deque<Long> w = windows.computeIfAbsent(clientId, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && w.peekFirst() <= cutoff) w.pollFirst();
            if (w.size() >= LIMIT_PER_MINUTE) return true;
            w.addLast(now);
            return false;
        }
    }

    private String extractClientId(byte[] body) {
        try {
            JsonNode json = JsonUtil.MAPPER.readTree(body);
            String id = json.path("client_id").asText(null);
            return (id != null && !id.isBlank()) ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        windows.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst() <= cutoff) d.pollFirst();
                return d.isEmpty();
            }
        });
        log.fine("RateLimitFilter cleanup done, remaining keys=" + windows.size());
    }

    // ── 바디 재사용 래퍼 ──────────────────────────────────────────────────
    private static class CachedBodyRequest extends HttpServletRequestWrapper {
        final byte[] body;

        CachedBodyRequest(HttpServletRequest req) throws IOException {
            super(req);
            body = req.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished()  { return bais.available() == 0; }
                @Override public boolean isReady()     { return true; }
                @Override public void setReadListener(ReadListener l) {}
                @Override public int read()            { return bais.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
