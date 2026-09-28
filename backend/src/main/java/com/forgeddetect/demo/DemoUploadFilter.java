package com.forgeddetect.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Phase 25: rejects any uploaded file that isn't demo media while the public demo is on. Checks every
 * multipart file part of every endpoint (public, moderator and partner API alike), so no route can be
 * used to put someone's real photo in front of the models on a shared instance.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 10)
public class DemoUploadFilter extends OncePerRequestFilter {

    static final String MESSAGE = "This public demo accepts only its built-in synthetic samples. "
            + "Choose one under \"Use a sample\", or run the project locally to try your own files.";
    static final String BUSY_MESSAGE = "The public demo is busy right now. Please try again in a moment.";

    private final DemoUploadGuard guard;
    private final ObjectMapper mapper;

    public DemoUploadFilter(DemoUploadGuard guard, ObjectMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return !guard.active() || type == null || !type.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<byte[]> files = new ArrayList<>();
        if (request instanceof MultipartHttpServletRequest multipart) {
            for (MultipartFile file : multipart.getMultiFileMap().values().stream().flatMap(List::stream).toList()) {
                files.add(file.getBytes());
            }
        } else {
            Collection<Part> parts;
            try {
                parts = request.getParts();
            } catch (IllegalStateException | ServletException ex) {
                // Oversized or malformed: let the normal multipart handling produce its usual error
                chain.doFilter(request, response);
                return;
            }
            for (Part part : parts) {
                if (part.getSubmittedFileName() != null) {
                    try (InputStream in = part.getInputStream()) {
                        files.add(in.readAllBytes());
                    }
                }
            }
        }
        for (byte[] bytes : files) {
            if (bytes.length > 0 && !guard.allowed(bytes)) {
                reject(response, HttpStatus.FORBIDDEN, "demo_samples_only", MESSAGE);
                return;
            }
        }
        if (!files.isEmpty()) {
            long retryAfter = guard.takeUpload();
            if (retryAfter > 0) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                reject(response, HttpStatus.TOO_MANY_REQUESTS, "demo_rate_limited", BUSY_MESSAGE);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", error, "message", message));
    }
}
