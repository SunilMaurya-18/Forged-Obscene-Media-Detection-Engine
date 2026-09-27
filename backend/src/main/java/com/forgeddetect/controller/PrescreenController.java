package com.forgeddetect.controller;

import com.forgeddetect.model.KnownHashSource;
import com.forgeddetect.service.PrescreenService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 18: "check before you post". Public, JSON only, and it accepts nothing but a hash: any other
 * field (an image, a filename, a user ID) is rejected rather than silently ignored.
 */
@RestController
public class PrescreenController {

    private static final Set<String> ALLOWED_FIELDS = Set.of("hash", "algorithm");

    private final PrescreenService prescreen;
    private final Clock clock;

    public PrescreenController(PrescreenService prescreen, Clock clock) {
        this.prescreen = prescreen;
        this.clock = clock;
    }

    @PostMapping(value = "/api/prescreen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> prescreen(@RequestBody Map<String, Object> body) {
        if (body == null || !ALLOWED_FIELDS.containsAll(body.keySet())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "only_hash_accepted",
                    "message", "Send only {\"hash\": \"<hex>\", \"algorithm\": \"phash\"}. Images and other fields are refused."));
        }
        if (!(body.get("hash") instanceof String hash)
                || (body.get("algorithm") != null && !(body.get("algorithm") instanceof String))) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_hash", "message", "hash must be a hex string"));
        }
        String algorithm = (String) body.get("algorithm");
        boolean match;
        try {
            match = prescreen.matches(hash, algorithm);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_hash", "message", ex.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("match", match);
        out.put("algorithm", algorithm == null ? "phash" : algorithm.trim().toLowerCase());
        out.put("checkedAt", clock.instant().toString());
        out.put("advice", match
                ? "This image matches content already confirmed as abusive. Please don't post it. "
                        + "If it is an image of you, you can ask the platform or StopNCII.org for help."
                : "No match in the known-hash list. That doesn't make the image safe or consensual; it only means it isn't on the list.");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(out);
    }

    /** Moderator view of the list size (never the hashes themselves). */
    @GetMapping("/api/admin/known-hashes")
    public Map<String, Object> summary() {
        return prescreen.summary();
    }

    /** Import a partner hash list. Body: {"source": "STOPNCII_IMPORT", "algorithm": "pdq", "hashes": [...]} */
    @PostMapping(value = "/api/admin/known-hashes/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> importHashes(@RequestBody ImportRequest request) {
        if (request == null || request.hashes() == null || request.hashes().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "hashes is required"));
        }
        if (request.hashes().size() > 10_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "import at most 10000 hashes per request"));
        }
        KnownHashSource source;
        try {
            source = request.source() == null ? KnownHashSource.STOPNCII_IMPORT : KnownHashSource.valueOf(request.source().trim().toUpperCase());
            PrescreenService.normalizeAlgorithm(request.algorithm());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>(prescreen.importHashes(
                request.hashes(), request.algorithm(), source, request.note() != null ? request.note() : "Imported " + source.name()));
        out.put("source", source.name());
        return ResponseEntity.ok(out);
    }

    public record ImportRequest(String source, String algorithm, List<String> hashes, String note) {}
}
