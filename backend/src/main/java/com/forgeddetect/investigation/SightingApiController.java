package com.forgeddetect.investigation;

import com.forgeddetect.security.ApiClientPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 26: partner platforms report where they have seen media ({@code POST /api/v1/sightings}),
 * authenticated and rate limited like the rest of {@code /api/v1}. The response confirms receipt only:
 * whether the sighting matched an investigation is never disclosed to a partner.
 */
@RestController
@RequestMapping("/api/v1")
public class SightingApiController {

    private final SightingService sightings;

    public SightingApiController(SightingService sightings) {
        this.sightings = sightings;
    }

    /** 201 for a new sighting, 200 when the same post was already reported. */
    @PostMapping(value = "/sightings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> report(
            @AuthenticationPrincipal ApiClientPrincipal client, @RequestBody(required = false) SightingRequest request) {
        if (request == null) {
            return invalid("Send the sighting as a JSON body.");
        }
        String platform = request.platform() == null || request.platform().isBlank()
                ? SightingService.platformFromName(client.getName())
                : request.platform();
        SightingService.Recorded recorded;
        try {
            recorded = sightings.report(
                    new SightingService.Report(request.phash(), request.provenanceId(), platform, request.postRef(),
                            request.accountId(), request.observedAt()),
                    SightingSource.PARTNER_API, client.id(), null);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
        Sighting s = recorded.sighting();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", s.getId());
        body.put("duplicate", recorded.duplicate());
        body.put("platform", s.getPlatform());
        body.put("postRef", s.getPostRef());
        body.put("accountRecorded", s.getAccountKey() != null);
        body.put("observedAt", s.getObservedAt().toString());
        body.put("receivedAt", s.getReportedAt().toString());
        return ResponseEntity.status(recorded.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    private static ResponseEntity<Map<String, Object>> invalid(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_sighting");
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }

    /** {@code accountId} is hashed with a platform-scoped key on arrival and never stored. */
    public record SightingRequest(
            String phash, String provenanceId, String platform, String postRef, String accountId, String observedAt) {}
}
