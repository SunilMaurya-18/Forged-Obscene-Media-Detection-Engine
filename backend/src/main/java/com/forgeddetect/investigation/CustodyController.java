package com.forgeddetect.investigation;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 27: custody log, preservation snapshots and legal holds of a case. Moderators only, like the case itself. */
@RestController
@RequestMapping("/api/investigations/{caseNumber}")
@PreAuthorize("hasRole('MODERATOR')")
public class CustodyController {

    private final InvestigationService investigations;
    private final CustodyLog custody;
    private final PreservationService preservations;
    private final LegalHoldService holds;

    public CustodyController(
            InvestigationService investigations, CustodyLog custody, PreservationService preservations, LegalHoldService holds) {
        this.investigations = investigations;
        this.custody = custody;
        this.preservations = preservations;
        this.holds = holds;
    }

    /** Every custody event with its place in the chain and hashes, and whether the chain verifies. */
    @GetMapping("/custody")
    @Transactional(readOnly = true)
    public Map<String, Object> custody(@PathVariable String caseNumber) {
        Investigation inv = investigations.find(caseNumber);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseNumber", inv.getCaseNumber());
        body.put("algorithm", "SHA-256(previous hash + \"\\n\" + " + CustodyChain.VERSION + " canonical event)");
        body.put("genesis", CustodyChain.GENESIS);
        body.put("verification", CustodyLog.verificationView(custody.verify(inv)));
        body.put("events", custody.events(inv).stream().map(CustodyLog::eventView).toList());
        return body;
    }

    @PostMapping("/preservations")
    public ResponseEntity<Map<String, Object>> preserve(
            @PathVariable String caseNumber, @RequestBody(required = false) PreserveRequest request, Principal principal) {
        PreservationService.Preserved done = preservations.preserve(caseNumber, request != null ? request.reason() : null, actor(principal));
        Map<String, Object> body = preservations.fullView(done.preservation());
        body.put("timestampStatus", done.timestampStatus());
        body.put("timestampError", done.timestampError());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/preservations")
    public List<Map<String, Object>> preservations(@PathVariable String caseNumber) {
        return preservations.list(investigations.find(caseNumber)).stream().map(PreservationService::view).toList();
    }

    @GetMapping("/preservations/{preservationId}")
    public Map<String, Object> preservation(@PathVariable String caseNumber, @PathVariable String preservationId) {
        return preservations.fullView(preservations.find(caseNumber, preservationId));
    }

    /** The signed bytes exactly, for hashing and checking the signature offline. */
    @GetMapping(value = "/preservations/{preservationId}/manifest", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> manifest(@PathVariable String caseNumber, @PathVariable String preservationId) {
        Preservation p = preservations.find(caseNumber, preservationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + p.getPreservationId() + ".manifest.txt\"")
                .header("X-Manifest-SHA256", p.getManifestHash())
                .header("X-Signature", p.getSignature())
                .header("X-Signing-Key-Id", p.getSigningKeyId())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(p.getManifest());
    }

    @GetMapping("/preservations/{preservationId}/verify")
    public Map<String, Object> verify(@PathVariable String caseNumber, @PathVariable String preservationId) {
        return PreservationService.verificationView(preservations.verify(caseNumber, preservationId));
    }

    /** {@code postRefs} empty or missing: every linked sighting on that platform in this case. */
    @PostMapping("/legal-holds")
    public ResponseEntity<Map<String, Object>> requestHold(
            @PathVariable String caseNumber, @RequestBody(required = false) HoldRequest request, Principal principal) {
        holds.request(caseNumber, request != null ? request.platform() : null, request != null ? request.postRefs() : null,
                request != null ? request.note() : null, actor(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(investigations.detail(caseNumber));
    }

    @PostMapping("/legal-holds/{holdNumber}/acknowledge")
    public Map<String, Object> acknowledge(
            @PathVariable String caseNumber, @PathVariable String holdNumber,
            @RequestBody(required = false) AcknowledgeRequest request, Principal principal) {
        holds.acknowledge(caseNumber, holdNumber, request != null ? request.partnerReference() : null, actor(principal));
        return investigations.detail(caseNumber);
    }

    @PostMapping("/legal-holds/{holdNumber}/release")
    public Map<String, Object> release(
            @PathVariable String caseNumber, @PathVariable String holdNumber,
            @RequestBody(required = false) ReleaseRequest request, Principal principal) {
        holds.release(caseNumber, holdNumber, request != null ? request.reason() : null, actor(principal));
        return investigations.detail(caseNumber);
    }

    private static String actor(Principal principal) {
        return principal != null ? principal.getName() : "moderator";
    }

    public record PreserveRequest(String reason) {}

    public record HoldRequest(String platform, List<String> postRefs, String note) {}

    public record AcknowledgeRequest(String partnerReference) {}

    public record ReleaseRequest(String reason) {}
}
