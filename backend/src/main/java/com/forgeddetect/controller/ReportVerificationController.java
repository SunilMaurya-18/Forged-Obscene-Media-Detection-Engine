package com.forgeddetect.controller;

import com.forgeddetect.service.ReportSigningService;
import java.util.LinkedHashMap;
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

    @GetMapping(value = "/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public String publicKey() {
        return signing.publicKeyPem();
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
        body.put("contentHash", v.contentHash());
        body.put("changedFields", v.changedFields());
        body.put("scope", "Development signing key: demonstrates the mechanism. Legal admissibility needs a KMS/HSM-held key "
                + "and a trusted third-party timestamp authority.");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
