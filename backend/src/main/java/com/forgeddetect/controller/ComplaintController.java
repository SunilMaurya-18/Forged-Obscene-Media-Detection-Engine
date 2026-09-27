package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Complaint;
import com.forgeddetect.service.CaseService;
import com.forgeddetect.service.SlaClockService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Phase 21: public self-service reporting. No account: filing returns a case number and a PIN, and
 * the status endpoint needs both. Responses carry plain-language codes (stage, verdict), never scores,
 * moderator identities or internal ids.
 */
@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {

    private final CaseService cases;

    public ComplaintController(CaseService cases) {
        this.cases = cases;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> file(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "consent", required = false) String consent,
            @RequestParam(value = "relationship", required = false) String relationship,
            @RequestParam(value = "details", required = false) String details,
            @RequestParam(value = "contact", required = false) String contact,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "jurisdiction", required = false) String jurisdiction)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "file_required", "Choose the image you want to report.");
        }
        if (!"true".equalsIgnoreCase(consent)) {
            return error(HttpStatus.BAD_REQUEST, "consent_required",
                    "Please confirm the image shows you, or that you have permission to report it for the person shown.");
        }
        CaseService.Filing filing = new CaseService.Filing(
                file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                relationship, details, contact, locale, jurisdiction);
        CaseService.Created created;
        try {
            created = cases.file(filing);
        } catch (CaseService.InvalidFiling ex) {
            return error(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage());
        }

        Map<String, Object> body = caseView(created.complaint(), created.item());
        body.put("pin", created.pin());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /** PIN goes in the {@code X-Case-Pin} header so it never lands in URLs, history or access logs. */
    @GetMapping("/{caseNumber}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String caseNumber, @RequestHeader(value = "X-Case-Pin", required = false) String pin) {
        Optional<CaseService.Status> found;
        try {
            found = cases.status(caseNumber, pin == null ? null : pin.trim());
        } catch (CaseService.TooManyAttempts ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                    .body(Map.of("error", "too_many_attempts",
                            "message", "Too many wrong PINs for this case. Please wait a few minutes and try again."));
        }
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "case_not_found",
                    "We couldn't find a case with that number and PIN. Check both and try again.");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(caseView(found.get().complaint(), found.get().item()));
    }

    private Map<String, Object> caseView(Complaint c, AnalysisResult item) {
        CaseService.Stage stage = CaseService.stage(item);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseNumber", c.getCaseNumber());
        body.put("stage", stage.name());
        body.put("verdict", cases.verdict(item).name());
        body.put("receivedAt", str(item.getComplaintReceivedAt()));
        body.put("deadline", str(item.getSlaDeadline()));
        body.put("resolvedAt", str(item.getResolvedAt()));
        body.put("withinDeadline", item.getResolvedAt() == null || item.getSlaDeadline() == null
                ? null : !item.getResolvedAt().isAfter(item.getSlaDeadline()));
        body.put("deadlineHours", SlaClockService.window(item).toMinutes() / 60.0);
        body.put("jurisdiction", item.getJurisdiction());
        body.put("rule", item.getSlaRuleLabel());
        body.put("notifyChannel", c.getContactChannel());
        body.put("locale", c.getLocale());

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(Map.of("stage", "RECEIVED", "at", str(item.getComplaintReceivedAt())));
        if (stage != CaseService.Stage.IN_REVIEW) {
            timeline.add(Map.of("stage", stage.name(), "at", str(item.getResolvedAt())));
        }
        body.put("timeline", timeline);
        return body;
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}
