package com.stockpilot.config;

import com.stockpilot.common.ApiError;
import com.stockpilot.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Fixed-window, per-IP limiter for the credential endpoints. In-memory: fine for a single instance. */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final ObjectMapper mapper;

    public AuthRateLimitFilter(AppProperties props, ObjectMapper mapper) {
        this.limit = props.rateLimit().authPerMinute();
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !("POST".equals(request.getMethod()) && (p.equals("/api/auth/login") || p.equals("/api/auth/register")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = req.getRemoteAddr();
        Window w = windows.merge(key, new Window(now, 1),
                (old, fresh) -> now - old.startMillis() > 60_000 ? fresh : new Window(old.startMillis(), old.count() + 1));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() > 60_000);
        }
        if (w.count() > limit) {
            res.setStatus(429);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(mapper.writeValueAsString(
                    ApiError.of(ErrorCode.RATE_LIMITED, "Too many attempts. Please wait a minute and try again.", null)));
            return;
        }
        chain.doFilter(req, res);
    }
}
