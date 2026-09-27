package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.service.AnalysisService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final AnalysisService analysisService;

    public QueueController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping
    @PreAuthorize("hasRole('MODERATOR')")
    public List<Map<String, Object>> queue() {
        return analysisService.queue().stream().map(this::toView).toList();
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('MODERATOR')")
    public ResponseEntity<Map<String, Object>> updateDecision(
            @PathVariable Long id, @RequestBody DecisionUpdateRequest request) {
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision is required"));
        }
        Decision decision;
        try {
            decision = Decision.valueOf(request.decision().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "decision must be one of AUTO_FLAG, REVIEW, ALLOW"));
        }
        AnalysisResult updated = analysisService.updateDecision(id, decision);
        return ResponseEntity.ok(toView(updated));
    }

    private Map<String, Object> toView(AnalysisResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.getId());
        body.put("phash", result.getPhash());
        body.put("forgeryScore", result.getForgeryScore());
        body.put("explicitScore", result.getExplicitScore());
        body.put("decision", result.getDecision().name());
        body.put("createdAt", result.getCreatedAt().toString());
        body.put("provenanceId", result.getProvenanceId());
        return body;
    }

    public record DecisionUpdateRequest(String decision) {}
}
