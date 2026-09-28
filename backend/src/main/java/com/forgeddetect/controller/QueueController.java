package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.service.ModerationAuditService;
import com.forgeddetect.service.ModerationService;
import com.forgeddetect.service.ReportService;
import com.forgeddetect.service.SlaClockService;
import com.forgeddetect.service.WellbeingService;
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

    private final ReportService reportService;
    private final ModerationService moderation;
    private final ModerationAuditService audit;
    private final WellbeingService wellbeing;

    public QueueController(
            ReportService reportService,
            ModerationService moderation,
            ModerationAuditService audit,
            WellbeingService wellbeing) {
        this.reportService = reportService;
        this.moderation = moderation;
        this.audit = audit;
        this.wellbeing = wellbeing;
    }

    /**
     * {@code status}: review (default, awaiting a moderator) | flagged (recent auto-flags) |
     * second-review (proposed AUTO_FLAG overrides) | escalated (senior queue).
     * {@code scope}: all (default) | mine | team | unassigned.
     */
    @GetMapping
    @PreAuthorize("hasRole('MODERATOR')")
    public List<Map<String, Object>> queue(
            @RequestParam(defaultValue = "review") String status,
            @RequestParam(defaultValue = "all") String scope,
            Principal principal) {
        String me = actor(principal);
        return moderation.queue(status, scope, me).stream()
                .map(r -> withConfirm(toView(r, me), r, me))
                .toList();
    }

    /** {@code canConfirm} with every server-side second-review rule applied, so the UI never offers a 403. */
    private Map<String, Object> withConfirm(Map<String, Object> view, AnalysisResult r, String me) {
        if (r.getPendingDecision() != null) {
            view.put("canConfirm", moderation.canSecondReview(r, me));
        }
        return view;
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
        wellbeing.requireNotOnBreak(actor(principal));
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
        } catch (ReportService.UnsupportedOriginalException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
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

    /**
     * 200 with the updated item, or 202 when the change moves an item away from AUTO_FLAG: that is
     * recorded as a proposal and applied only after a different moderator confirms it.
     */
    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('MODERATOR')")
    public ResponseEntity<Map<String, Object>> updateDecision(
            @PathVariable Long id, @RequestBody DecisionUpdateRequest request, Principal principal) {
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
        String me = actor(principal);
        ModerationService.Outcome outcome = moderation.decide(id, decision, me, request.reason());
        Map<String, Object> body = toView(outcome.item(), me);
        body.put("pendingSecondReview", outcome.pendingSecondReview());
        body.put("wellbeing", wellbeing.status(me).view());
        return ResponseEntity.status(outcome.pendingSecondReview() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(body);
    }

    /** Confirms ({@code confirm: true}) or rejects a pending AUTO_FLAG override. */
    @PostMapping("/{id}/second-review")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> secondReview(
            @PathVariable Long id, @RequestBody SecondReviewRequest request, Principal principal) {
        String me = actor(principal);
        AnalysisResult updated = moderation.secondReview(
                id, request != null && Boolean.TRUE.equals(request.confirm()), request != null ? request.note() : null, me);
        Map<String, Object> body = toView(updated, me);
        body.put("wellbeing", wellbeing.status(me).view());
        return body;
    }

    /**
     * {@code assignee}: omitted or blank = take it yourself; another moderator needs SENIOR_MODERATOR.
     * {@code team} (without {@code assignee}): route to a team's pool, SENIOR_MODERATOR only.
     */
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> assign(
            @PathVariable Long id, @RequestBody(required = false) AssignRequest request, Principal principal) {
        String me = actor(principal);
        return toView(moderation.assign(id, request != null ? request.assignee() : null,
                request != null ? request.team() : null, me), me);
    }

    @PostMapping("/{id}/unassign")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> unassign(@PathVariable Long id, Principal principal) {
        String me = actor(principal);
        return toView(moderation.unassign(id, me), me);
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> escalate(
            @PathVariable Long id, @RequestBody(required = false) EscalateRequest request, Principal principal) {
        String me = actor(principal);
        return toView(moderation.escalate(id, request != null ? request.reason() : null, me), me);
    }

    /** Logs that the moderator chose to see this item unblurred; counts toward the exposure limit. */
    @PostMapping("/{id}/reveal")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> reveal(@PathVariable Long id, Principal principal) {
        return Map.of("revealed", true, "wellbeing", moderation.reveal(id, actor(principal)).view());
    }

    /** Internal moderation log for one item (names moderators; never shown publicly). */
    @GetMapping("/{id}/audit")
    @PreAuthorize("hasRole('MODERATOR')")
    public List<Map<String, Object>> auditTrail(@PathVariable Long id) {
        return audit.view(id);
    }

    private static String actor(Principal principal) {
        return principal != null ? principal.getName() : "moderator";
    }

    static Map<String, Object> toView(AnalysisResult result, String viewer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.getId());
        body.put("phash", result.getPhash());
        body.put("forgeryScore", result.getForgeryScore());
        body.put("explicitScore", result.getExplicitScore());
        body.put("generationFamily", result.getGenerationFamily());
        body.put("decision", result.getDecision().name());
        body.put("modelDecision", result.getModelDecision() != null ? result.getModelDecision().name() : null);
        body.put("reviewedAt", result.getReviewedAt() != null ? result.getReviewedAt().toString() : null);
        body.put("createdAt", result.getCreatedAt().toString());
        body.put("provenanceId", result.getProvenanceId());
        body.put("hasForensics", result.getForensicSignature() != null);
        body.put("jurisdiction", result.getJurisdiction());
        body.put("complaintType", result.getComplaintType() != null ? result.getComplaintType().name() : null);
        body.put("slaDeadline", result.getSlaDeadline() != null ? result.getSlaDeadline().toString() : null);
        body.put("slaBadge", SlaClockService.badge(result.getSlaRuleLabel(), SlaClockService.window(result)));
        body.put("reportId", result.getReportId());
        MediaViews.put(body, result);
        body.put("decidedBy", result.getDecidedBy());
        body.put("assignedTo", result.getAssignedTo());
        body.put("assignedTeam", result.getAssignedTeam());
        body.put("routedTeam", result.getRoutedTeam());
        body.put("seniorEscalated", result.isSeniorEscalated());
        body.put("seniorResolvedBy", result.getSeniorResolvedBy());
        body.put("seniorEscalatedBy", result.getSeniorEscalatedBy());
        body.put("seniorEscalationReason", result.getSeniorEscalationReason());
        body.put("pendingDecision", result.getPendingDecision() != null ? result.getPendingDecision().name() : null);
        body.put("pendingProposedBy", result.getPendingProposedBy());
        body.put("pendingProposedAt", result.getPendingProposedAt() != null ? result.getPendingProposedAt().toString() : null);
        body.put("pendingReason", result.getPendingReason());
        body.put("canConfirm", result.getPendingDecision() != null && viewer != null && !viewer.equals(result.getPendingProposedBy()));
        body.put("noticeNumber", result.getNoticeNumber());
        body.put("appealStatus", result.getAppealStatus());
        return body;
    }

    public record DecisionUpdateRequest(String decision, String reason) {}

    public record SecondReviewRequest(Boolean confirm, String note) {}

    public record AssignRequest(String assignee, String team) {}

    public record EscalateRequest(String reason) {}
}
