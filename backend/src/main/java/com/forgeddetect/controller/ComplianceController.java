package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.JurisdictionRule;
import com.forgeddetect.repository.JurisdictionRuleRepository;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ComplianceService;
import com.forgeddetect.service.SlaClockService;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Phase 13: takedown SLA tracking; Phase 19: per-jurisdiction deadlines.
 * Filing a complaint is public (platform report buttons, agency portals); reading the queue is moderator-only.
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceService complianceService;
    private final SlaClockService slaClock;
    private final JurisdictionRuleRepository rules;

    public ComplianceController(ComplianceService complianceService, SlaClockService slaClock, JurisdictionRuleRepository rules) {
        this.complianceService = complianceService;
        this.slaClock = slaClock;
        this.rules = rules;
    }

    @PostMapping(value = "/complaints", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> fileComplaint(
            @RequestPart("file") MultipartFile file,
            @RequestParam("complaintType") String complaintType,
            @RequestParam(value = "reference", required = false) String reference,
            @RequestParam(value = "jurisdiction", required = false) String jurisdiction)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        ComplaintType type;
        try {
            type = ComplaintType.valueOf(complaintType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "complaintType must be USER_COMPLAINT or GOVERNMENT_ORDER"));
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (ref != null && ref.length() > 128) {
            return ResponseEntity.badRequest().body(Map.of("error", "reference must be at most 128 characters"));
        }

        try {
            slaClock.normalizeJurisdiction(jurisdiction);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        AnalysisService.AnalyzeOutcome outcome = complianceService.fileComplaint(
                file.getBytes(), file.getOriginalFilename(), file.getContentType(), type, ref, jurisdiction);
        Map<String, Object> body = toView(outcome.result());
        body.put("heatmap", outcome.heatmap() != null ? outcome.heatmap() : "");
        AnalyzeController.putProvenance(body, outcome);
        return ResponseEntity.ok(body);
    }

    /** Open complaints, nearest deadline first. */
    @GetMapping("/queue")
    public List<Map<String, Object>> queue() {
        return complianceService.openQueue().stream().map(this::toView).toList();
    }

    @GetMapping("/breaches")
    public List<Map<String, Object>> breaches() {
        return complianceService.breaches().stream().map(this::toView).toList();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return complianceService.summary();
    }

    /** Every jurisdiction rule, straight from the table (rows added in the DB show up here immediately). */
    @GetMapping("/rules")
    public Map<String, Object> rules() {
        List<Map<String, Object>> rows = rules.findAllByOrderByJurisdictionAscComplaintTypeAsc().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("jurisdiction", r.getJurisdiction());
            row.put("complaintType", r.getComplaintType().name());
            row.put("deadlineHours", r.getDeadlineHours());
            row.put("label", r.getLabel());
            row.put("legalReference", r.getLegalReference());
            row.put("badge", SlaClockService.badge(r.getLabel(), Duration.ofHours(r.getDeadlineHours())));
            return row;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultJurisdiction", slaClock.normalizeJurisdiction(null));
        body.put("fallback", JurisdictionRule.GENERIC);
        body.put("rules", rows);
        return body;
    }

    private Map<String, Object> toView(AnalysisResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.getId());
        body.put("phash", r.getPhash());
        body.put("forgeryScore", r.getForgeryScore());
        body.put("explicitScore", r.getExplicitScore());
        body.put("generationFamily", r.getGenerationFamily());
        body.put("decision", r.getDecision().name());
        body.put("createdAt", r.getCreatedAt().toString());
        body.put("complaintType", r.getComplaintType() != null ? r.getComplaintType().name() : null);
        body.put("complaintReference", r.getComplaintReference());
        body.put("jurisdiction", r.getJurisdiction());
        body.put("slaRuleLabel", r.getSlaRuleLabel());
        Duration window = SlaClockService.window(r);
        body.put("slaWindowMinutes", window != null ? window.toMinutes() : null);
        body.put("slaBadge", SlaClockService.badge(r.getSlaRuleLabel(), window));
        body.put("complaintReceivedAt", str(r.getComplaintReceivedAt()));
        body.put("slaDeadline", str(r.getSlaDeadline()));
        body.put("slaStatus", r.getSlaStatus() != null ? r.getSlaStatus().name() : null);
        body.put("remainingSeconds", r.getSlaDeadline() != null ? slaClock.remaining(r).toSeconds() : null);
        body.put("escalatedAt", str(r.getEscalatedAt()));
        body.put("resolvedAt", str(r.getResolvedAt()));
        body.put("provenanceId", r.getProvenanceId());
        body.put("hasForensics", r.getForensicSignature() != null);
        body.put("reportId", r.getReportId());
        return body;
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }
}
