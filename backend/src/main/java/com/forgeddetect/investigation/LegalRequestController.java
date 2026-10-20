package com.forgeddetect.investigation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 28: legal requests. Any moderator records one; a senior moderator who did not file it
 * approves or rejects it; a senior can revoke an approved one.
 */
@RestController
@RequestMapping("/api/legal-requests")
@PreAuthorize("hasRole('MODERATOR')")
public class LegalRequestController {

    private final LegalRequestService requests;

    public LegalRequestController(LegalRequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        return requests.list(auth.getName(), senior(auth));
    }

    /** Investigator accounts a request can name. */
    @GetMapping("/investigators")
    public List<Map<String, Object>> investigators() {
        return requests.investigators();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> file(@RequestBody(required = false) FileRequest body, Authentication auth) {
        LegalRequest r = requests.file(body == null ? null : new LegalRequestService.Filing(
                body.authority(), body.orderReference(), body.investigator(), body.caseNumbers(), body.expiresAt(), body.note()), auth.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.view(r, auth.getName(), senior(auth)));
    }

    @PostMapping("/{requestNumber}/approve")
    public Map<String, Object> approve(@PathVariable String requestNumber, @RequestBody(required = false) NoteRequest body, Authentication auth) {
        LegalRequest r = requests.decide(requestNumber, true, body != null ? body.note() : null, auth.getName(), senior(auth), admin(auth));
        return requests.view(r, auth.getName(), senior(auth));
    }

    @PostMapping("/{requestNumber}/reject")
    public Map<String, Object> reject(@PathVariable String requestNumber, @RequestBody(required = false) NoteRequest body, Authentication auth) {
        LegalRequest r = requests.decide(requestNumber, false, body != null ? body.note() : null, auth.getName(), senior(auth), admin(auth));
        return requests.view(r, auth.getName(), senior(auth));
    }

    @PostMapping("/{requestNumber}/revoke")
    public Map<String, Object> revoke(@PathVariable String requestNumber, @RequestBody(required = false) NoteRequest body, Authentication auth) {
        LegalRequest r = requests.revoke(requestNumber, body != null ? body.note() : null, auth.getName(), senior(auth));
        return requests.view(r, auth.getName(), senior(auth));
    }

    private static boolean senior(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SENIOR_MODERATOR".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public record FileRequest(String authority, String orderReference, String investigator, List<String> caseNumbers,
            Instant expiresAt, String note) {}

    public record NoteRequest(String note) {}
}
