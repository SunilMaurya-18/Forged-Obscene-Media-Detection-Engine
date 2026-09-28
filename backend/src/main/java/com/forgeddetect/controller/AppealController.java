package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.AppealService;
import com.forgeddetect.service.PinAttemptGuard;
import com.forgeddetect.service.WellbeingService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 23: appeals. Filing and status are public (notice number + PIN, like Phase 21 cases); the
 * queue and decisions are moderator-only.
 */
@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    private final AppealService appeals;
    private final AnalysisResultRepository results;
    private final WellbeingService wellbeing;

    public AppealController(AppealService appeals, AnalysisResultRepository results, WellbeingService wellbeing) {
        this.appeals = appeals;
        this.results = results;
        this.wellbeing = wellbeing;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> file(@RequestBody(required = false) FileRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", "Send noticeNumber, pin and statement."));
        }
        try {
            Appeal a = appeals.file(request.noticeNumber(), trim(request.pin()), request.statement());
            return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(AppealService.appealPublic(a));
        } catch (PinAttemptGuard.TooManyAttempts ex) {
            return tooMany(ex);
        }
    }

    /** PIN goes in the {@code X-Case-Pin} header so it never lands in URLs or access logs. */
    @GetMapping("/{noticeNumber}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String noticeNumber, @RequestHeader(value = "X-Case-Pin", required = false) String pin) {
        Optional<AppealService.Status> found;
        try {
            found = appeals.status(noticeNumber, trim(pin));
        } catch (PinAttemptGuard.TooManyAttempts ex) {
            return tooMany(ex);
        }
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found",
                    "message", "We couldn't find an upload with that notice number and PIN. Check both and try again."));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(appeals.publicView(found.get()));
    }

    /** {@code status=open} (default, nearest deadline first) or {@code decided}. */
    @GetMapping
    @PreAuthorize("hasRole('MODERATOR')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "open") String status, Principal principal) {
        String me = actor(principal);
        return appeals.list(status).stream()
                .map(a -> appeals.moderatorView(a, results.findById(a.getAnalysisId()).orElse(null), me))
                .toList();
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody DecisionRequest request, Principal principal) {
        String me = actor(principal);
        Appeal a = appeals.decide(id, request != null ? request.outcome() : null, request != null ? request.reason() : null, me);
        AnalysisResult item = results.findById(a.getAnalysisId()).orElse(null);
        Map<String, Object> body = appeals.moderatorView(a, item, me);
        body.put("wellbeing", wellbeing.status(me).view());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> tooMany(PinAttemptGuard.TooManyAttempts ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(Map.of("error", "too_many_attempts",
                        "message", "Too many wrong PINs for this notice. Please wait a few minutes and try again."));
    }

    private static String actor(Principal principal) {
        return principal != null ? principal.getName() : "moderator";
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    public record FileRequest(String noticeNumber, String pin, String statement) {}

    public record DecisionRequest(String outcome, String reason) {}
}
