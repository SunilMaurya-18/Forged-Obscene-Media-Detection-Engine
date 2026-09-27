package com.forgeddetect.controller;

import com.forgeddetect.model.ApiClient;
import com.forgeddetect.model.PlanTier;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.ApiKeyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Moderator-only management of {@code /api/v1} clients. Keys are returned once, on create/rotate. */
@RestController
@RequestMapping("/api/admin/clients")
@PreAuthorize("hasRole('MODERATOR')")
public class ApiClientAdminController {

    private final ApiKeyService keys;
    private final AnalysisResultRepository analyses;

    public ApiClientAdminController(ApiKeyService keys, AnalysisResultRepository analyses) {
        this.keys = keys;
        this.analyses = analyses;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (PlanTier plan : PlanTier.values()) {
            limits.put(plan.name(), keys.limitFor(plan));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plans", limits);
        body.put("clients", keys.list().stream().map(this::view).toList());
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CreateRequest request) {
        ApiKeyService.IssuedKey issued = keys.create(
                request != null ? request.name() : null, parsePlan(request != null ? request.plan() : null));
        return ResponseEntity.status(HttpStatus.CREATED).body(withKey(issued));
    }

    @PostMapping("/{id}/rotate")
    public Map<String, Object> rotate(@PathVariable Long id) {
        return withKey(keys.rotate(id));
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id) {
        return view(keys.revoke(id));
    }

    @PostMapping("/{id}/plan")
    public Map<String, Object> plan(@PathVariable Long id, @RequestBody(required = false) CreateRequest request) {
        PlanTier plan = parsePlan(request != null ? request.plan() : null);
        return view(keys.changePlan(id, plan));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", String.valueOf(ex.getReason())));
    }

    private Map<String, Object> withKey(ApiKeyService.IssuedKey issued) {
        Map<String, Object> body = view(issued.client());
        body.put("apiKey", issued.apiKey());
        return body;
    }

    private Map<String, Object> view(ApiClient c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", c.getId());
        body.put("name", c.getName());
        body.put("keyPrefix", c.getKeyPrefix());
        body.put("plan", c.getPlan().name());
        body.put("limitPerMinute", keys.limitFor(c));
        body.put("requestCount", c.getRequestCount());
        body.put("throttledCount", c.getThrottledCount());
        body.put("analysisCount", analyses.countByApiClientId(c.getId()));
        body.put("createdAt", c.getCreatedAt().toString());
        body.put("lastUsedAt", c.getLastUsedAt() != null ? c.getLastUsedAt().toString() : null);
        body.put("revokedAt", c.getRevokedAt() != null ? c.getRevokedAt().toString() : null);
        body.put("active", c.isActive());
        return body;
    }

    private static PlanTier parsePlan(String plan) {
        if (plan == null || plan.isBlank()) {
            return PlanTier.FREE;
        }
        try {
            return PlanTier.valueOf(plan.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "plan must be one of " + List.of(PlanTier.values()));
        }
    }

    public record CreateRequest(String name, String plan) {}
}
