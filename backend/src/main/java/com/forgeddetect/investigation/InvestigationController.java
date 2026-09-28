package com.forgeddetect.investigation;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.service.ModerationException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 26: investigation cases for moderators. Names moderators and account pseudonyms, so never public. */
@RestController
@RequestMapping("/api/investigations")
@PreAuthorize("hasRole('MODERATOR')")
public class InvestigationController {

    private final InvestigationService investigations;
    private final SightingService sightings;

    public InvestigationController(InvestigationService investigations, SightingService sightings) {
        this.investigations = investigations;
        this.sightings = sightings;
    }

    /** {@code status}: open (default) | closed | all; {@code analysisId}: only cases containing that analysis. */
    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "open") String status, @RequestParam(required = false) Long analysisId) {
        return investigations.list(status, analysisId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> open(@RequestBody(required = false) OpenRequest request, Principal principal) {
        Investigation inv = investigations.open(
                request != null ? request.analysisId() : null, request != null ? request.title() : null, actor(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(investigations.detail(inv.getCaseNumber()));
    }

    @GetMapping("/{caseNumber}")
    public Map<String, Object> detail(@PathVariable String caseNumber) {
        return investigations.detail(caseNumber);
    }

    @PostMapping("/{caseNumber}/items")
    public Map<String, Object> addItem(
            @PathVariable String caseNumber, @RequestBody(required = false) ItemRequest request, Principal principal) {
        Investigation inv = investigations.addItem(caseNumber, request != null ? request.analysisId() : null, actor(principal));
        return investigations.detail(inv.getCaseNumber());
    }

    /**
     * A sighting a moderator was told about (e.g. a victim naming where they saw the image). It carries
     * the hash of a case analysis, so it links to this case and to any other open case with the same media.
     */
    @PostMapping("/{caseNumber}/sightings")
    public ResponseEntity<Map<String, Object>> recordSighting(
            @PathVariable String caseNumber, @RequestBody(required = false) SightingRequest request, Principal principal) {
        if (request == null) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_sighting", "Enter the platform and post ID.");
        }
        AnalysisResult item = investigations.itemForSighting(caseNumber, request.analysisId());
        SightingService.Recorded recorded;
        try {
            recorded = sightings.report(
                    new SightingService.Report(Hashes.isPhash(item.getPhash()) ? item.getPhash() : null, item.getProvenanceId(),
                            request.platform(), request.postRef(), request.accountId(), request.observedAt()),
                    SightingSource.MODERATOR, null, actor(principal));
        } catch (IllegalArgumentException ex) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_sighting", ex.getMessage());
        }
        Investigation inv = investigations.find(caseNumber);
        return ResponseEntity.status(recorded.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(investigations.detail(inv.getCaseNumber()));
    }

    @PostMapping("/{caseNumber}/notes")
    public Map<String, Object> note(
            @PathVariable String caseNumber, @RequestBody(required = false) NoteRequest request, Principal principal) {
        Investigation inv = investigations.note(caseNumber, request != null ? request.text() : null, actor(principal));
        return investigations.detail(inv.getCaseNumber());
    }

    @PostMapping("/{caseNumber}/close")
    public Map<String, Object> close(
            @PathVariable String caseNumber, @RequestBody(required = false) CloseRequest request, Principal principal) {
        Investigation inv = investigations.close(caseNumber, request != null ? request.reason() : null, actor(principal));
        return investigations.detail(inv.getCaseNumber());
    }

    @PostMapping("/{caseNumber}/reopen")
    public Map<String, Object> reopen(@PathVariable String caseNumber, Principal principal) {
        Investigation inv = investigations.reopen(caseNumber, actor(principal));
        return investigations.detail(inv.getCaseNumber());
    }

    private static String actor(Principal principal) {
        return principal != null ? principal.getName() : "moderator";
    }

    public record OpenRequest(Long analysisId, String title) {}

    public record ItemRequest(Long analysisId) {}

    public record SightingRequest(Long analysisId, String platform, String postRef, String accountId, String observedAt) {}

    public record NoteRequest(String text) {}

    public record CloseRequest(String reason) {}
}
