package com.cacheguard.filter;

import com.cacheguard.model.RateLimitResult;
import com.cacheguard.service.RateLimiterService;
import com.cacheguard.service.StatsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway filter in front of every {@code /api} route.
 *
 * <p>Counts each request via {@link StatsService#incrementTotalRequests()}
 * and runs the sliding-window rate limiter. When a client exceeds the
 * limit the request is rejected with {@code 429} and the blocked counter
 * is bumped.</p>
 */
@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api";
    private static final int MAX_REQUESTS = 200;
    private static final int WINDOW_SECONDS = 60;

    private final RateLimiterService rateLimiterService;
    private final StatsService statsService;

    public RateLimiterFilter(RateLimiterService rateLimiterService,
                             StatsService statsService) {
        this.rateLimiterService = rateLimiterService;
        this.statsService = statsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(API_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Every API request counts towards the total.
        statsService.incrementTotalRequests();

        RateLimitResult result = rateLimiterService.isAllowed(
                resolveIp(request), path, MAX_REQUESTS, WINDOW_SECONDS);

        if (!result.allowed()) {
            statsService.incrementBlockedRequests();
            response.setStatus(429); // SC_TOO_MANY_REQUESTS is not defined by Jakarta Servlet
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                    "{\"error\":\"rate limit exceeded\",\"count\":%d,\"windowSeconds\":%d}",
                    result.count(), WINDOW_SECONDS));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
