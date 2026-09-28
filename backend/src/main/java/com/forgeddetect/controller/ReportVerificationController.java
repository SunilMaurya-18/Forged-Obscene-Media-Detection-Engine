package com.forgeddetect.controller;

import com.forgeddetect.service.ReportSigningService;
import com.forgeddetect.signing.ReportTimestampService;
import com.forgeddetect.signing.SigningKeyProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 20: public integrity check for evidentiary reports. Anyone holding a report ID can ask
 * whether the signed report still matches the record. Always 200; the verdict is in {@code status}.
 */
@RestController
@RequestMapping("/api/verify-report")
public class ReportVerificationController {

    private final ReportSigningService signing;

    public ReportVerificationController(ReportSigningService signing) {
        this.signing = signing;
    }

    /** The active key. Reports signed by retired keys verify against {@code /keys}. */
    @GetMapping(value = "/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public String publicKey() {
        return signing.publicKeyPem();
    }

    /** Phase 24: every key in the keyring (active and retired), so reports can be checked offline after rotation. */
    @GetMapping("/keys")
    public Map<String, Object> keys() {
        SigningKeyProvider provider = signing.provider();
        List<Map<String, Object>> keys = provider.keys().stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyId", k.keyId());
            m.put("status", k.active() ? "ACTIVE" : "RETIRED");
            m.put("createdAt", k.createdAt().toString());
            m.put("algorithm", ReportSigningService.ALGORITHM);
            m.put("publicKey", k.publicKeyPem());
            return m;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider.kind());
        body.put("activeKeyId", provider.activeKeyId());
        body.put("keyIdRule", "First 16 hex characters of the SHA-256 of the DER-encoded public key");
        body.put("keys", keys);
        return body;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String id) {
        ReportSigningService.Verification v = signing.verify(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", v.status().name());
        body.put("message", v.message());
        body.put("reportId", v.reportId());
        body.put("analysisId", v.analysisId());
        body.put("signedAt", v.signedAt() != null ? v.signedAt().toString() : null);
        body.put("algorithm", ReportSigningService.ALGORITHM);
        body.put("keyId", v.keyId());
        body.put("keyStatus", v.keyStatus());
        body.put("keyProvider", signing.provider().kind());
        body.put("contentHash", v.contentHash());
        body.put("changedFields", v.changedFields());
        body.put("timestamp", timestamp(v.timestamp()));
        body.put("scope", scope(v.timestamp()));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    private static Map<String, Object> timestamp(ReportTimestampService.Check ts) {
        if (ts == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", ts.status().name());
        m.put("time", ts.time() != null ? ts.time().toString() : null);
        m.put("authority", ts.authority());
        m.put("trusted", ts.trusted());
        m.put("message", ts.message());
        return m;
    }

    private String scope(ReportTimestampService.Check ts) {
        String key = signing.provider().production()
                ? "Signing key held in " + signing.provider().kind() + " (the private key never leaves it)."
                : "Development signing key on the server's disk: demonstrates the mechanism only.";
        String time;
        if (ts != null && ts.trusted()) {
            time = " Signing time attested by a trusted RFC 3161 timestamp authority.";
        } else if (ts != null) {
            time = " Carries an RFC 3161 timestamp from an authority not on this server's trust list.";
        } else {
            time = " No third-party timestamp: the signing time is this server's clock.";
        }
        return key + time;
    }
}
