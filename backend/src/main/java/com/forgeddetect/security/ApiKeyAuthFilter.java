package com.forgeddetect.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.ApiClient;
import com.forgeddetect.observability.FmdMetrics;
import com.forgeddetect.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase 16: authenticates {@code /api/v1/**} by API key ({@code X-API-Key} or
 * {@code Authorization: Bearer}), then applies the client's per-minute rate limit. Moderator
 * Basic/session auth is not accepted on this path.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    static final String ROLE = "ROLE_API_CLIENT";

    private final ApiKeyService keys;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;
    private final FmdMetrics metrics;

    public ApiKeyAuthFilter(ApiKeyService keys, RateLimiter rateLimiter, ObjectMapper mapper, FmdMetrics metrics) {
        this.keys = keys;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String presented = presentedKey(request);
        if (presented == null) {
            unauthorized(response, "missing_api_key", "Send your API key in the X-API-Key header.");
            return;
        }
        Optional<ApiClient> found = keys.authenticate(presented);
        if (found.isEmpty()) {
            unauthorized(response, "invalid_api_key", "The API key is not valid or has been revoked.");
            return;
        }
        ApiClient client = found.get();
        int limit = keys.limitFor(client);

        // Checking usage is free so a throttled client can still see when its quota refills.
        boolean metered = !isUsage(request);
        RateLimiter.Result rate = metered
                ? rateLimiter.tryConsume(client.getId(), limit)
                : rateLimiter.peek(client.getId(), limit);
        response.setHeader("X-RateLimit-Limit", String.valueOf(rate.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(rate.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(rate.resetSeconds()));
        if (metered && !rate.allowed()) {
            keys.recordThrottled(client.getId());
            metrics.rateLimited(client.getPlan() != null ? client.getPlan().name() : null);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rate.retryAfterSeconds()));
            Map<String, Object> body = error("rate_limited",
                    "Rate limit of " + rate.limit() + " requests per minute exceeded for plan " + client.getPlan()
                            + ". Retry after " + rate.retryAfterSeconds() + " s.");
            body.put("retryAfterSeconds", rate.retryAfterSeconds());
            write(response, HttpStatus.TOO_MANY_REQUESTS, body);
            return;
        }
        if (metered) {
            keys.recordRequest(client.getId());
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new ApiClientPrincipal(client.getId(), client.getName(), client.getPlan()),
                null,
                List.of(new SimpleGrantedAuthority(ROLE))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean isUsage(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) && request.getRequestURI().endsWith("/api/v1/usage");
    }

    private static String presentedKey(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + HEADER + "\"");
        write(response, HttpStatus.UNAUTHORIZED, error(code, message));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    private void write(HttpServletResponse response, HttpStatus status, Map<String, Object> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
