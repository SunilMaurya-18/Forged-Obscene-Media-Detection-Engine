package com.forgeddetect.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase 24: gives every request an ID (the caller's {@code X-Request-ID} when well formed, e.g. from
 * nginx, otherwise a new one), puts it in the logging MDC as {@code request_id}, echoes it on the
 * response, and {@link com.forgeddetect.config.WebClientConfig} forwards it to the ML service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    /** One line per request, like the ML service's; {@code logging.level.access=WARN} turns it off. */
    private static final Logger access = LoggerFactory.getLogger("access");

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "request_id";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            // Tomcat would log this after the MDC is cleared, losing the request ID; log it here instead
            // and let the container render the usual /error response
            if (response.isCommitted()) {
                throw ex;
            }
            log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            logAccess(request, response, started);
            MDC.remove(MDC_KEY);
        }
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response, long started) {
        String path = request.getRequestURI();
        if (!access.isInfoEnabled() || path.startsWith("/actuator")) {
            return;
        }
        double ms = Math.round((System.nanoTime() - started) / 100_000.0) / 10.0;
        // Path only: query strings can carry lookup codes, so they stay out of the logs
        access.atInfo()
                .addKeyValue("http_method", request.getMethod())
                .addKeyValue("http_path", path)
                .addKeyValue("http_status", response.getStatus())
                .addKeyValue("duration_ms", ms)
                .log("{} {} {} {}ms", request.getMethod(), path, response.getStatus(), ms);
    }
}
