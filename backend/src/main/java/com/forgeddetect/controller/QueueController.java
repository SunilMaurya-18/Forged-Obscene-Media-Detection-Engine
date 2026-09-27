package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ReportService;
import com.forgeddetect.service.SlaClockService;
import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final AnalysisService analysisService;
    private final ReportService reportService;

    public QueueController(AnalysisService analysisService, ReportService reportService) {
        this.analysisService = analysisService;
        this.reportService = reportService;
    }

    /** {@code status=review} (default): items awaiting a moderator. {@code status=flagged}: recent auto-flags. */
    @GetMapping
    @PreAuthorize("hasRole('MODERATOR')")
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "review") String status) {
        List<AnalysisResult> items = "flagged".equalsIgnoreCase(status) ? analysisService.flagged() : analysisService.queue();
        return items.stream().map(this::toView).toList();
    }

    /**
     * Evidentiary PDF for any analysis. Optional multipart {@code file}: the original media, used for
     * full-resolution panels after verifying it against the stored pixel hash; never stored.
     */
    @PostMapping("/{id}/report")
    @PreAuthorize("hasRole('MODERATOR')")
    public ResponseEntity<?> report(
            @PathVariable Long id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            Principal principal)
            throws IOException {
        byte[] original = file != null && !file.isEmpty() ? file.getBytes() : null;
        ReportService.Report report;
        try {
            report = reportService.generate(
                    id,
                    original,
                    file != null ? file.getOriginalFilename() : null,
                    file != null ? file.getContentType() : null,
                    principal != null ? principal.getName() : "moderator");
        } catch (ReportService.MediaMismatchException ex) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException ex) {
            if (original == null) {
                throw ex;
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Could not render panels from the attached file: " + ex.getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(report.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Report-Id", report.reportId())
                .header("X-Report-Sha256", report.sha256())
                .header("X-Report-Panels", report.panelSource().name())
                .header("X-Report-Signing-Key", report.signature().keyId())
                .header("X-Report-Signed-Content-Sha256", report.signature().contentHash())
                .body(report.pdf());
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
        body.put("generationFamily", result.getGenerationFamily());
        body.put("decision", result.getDecision().name());
        body.put("createdAt", result.getCreatedAt().toString());
        body.put("provenanceId", result.getProvenanceId());
        body.put("hasForensics", result.getForensicSignature() != null);
        body.put("jurisdiction", result.getJurisdiction());
        body.put("complaintType", result.getComplaintType() != null ? result.getComplaintType().name() : null);
        body.put("slaDeadline", result.getSlaDeadline() != null ? result.getSlaDeadline().toString() : null);
        body.put("slaBadge", SlaClockService.badge(result.getSlaRuleLabel(), SlaClockService.window(result)));
        body.put("reportId", result.getReportId());
        return body;
    }

    public record DecisionUpdateRequest(String decision) {}
}
