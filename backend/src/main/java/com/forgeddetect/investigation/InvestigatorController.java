package com.forgeddetect.investigation;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 28: the investigator's read-only view. INVESTIGATOR accounts reach nothing else, and here
 * only the cases an approved, unexpired legal request names ({@code 403 no_legal_authority} otherwise).
 */
@RestController
@RequestMapping("/api/investigator")
@PreAuthorize("hasRole('INVESTIGATOR')")
public class InvestigatorController {

    private final InvestigatorService investigator;
    private final EvidencePackageService packages;

    public InvestigatorController(InvestigatorService investigator, EvidencePackageService packages) {
        this.investigator = investigator;
        this.packages = packages;
    }

    @GetMapping("/cases")
    public List<Map<String, Object>> cases(Principal principal) {
        return investigator.cases(principal.getName());
    }

    /** Recorded in the case's custody log as INVESTIGATOR_VIEWED. */
    @GetMapping("/cases/{caseNumber}")
    public Map<String, Object> view(@PathVariable String caseNumber, Principal principal) {
        return investigator.view(caseNumber, principal.getName());
    }

    @PostMapping("/cases/{caseNumber}/packages")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable String caseNumber, Principal principal) {
        EvidencePackage p = packages.issue(caseNumber, principal.getName());
        Map<String, Object> body = EvidencePackageService.view(p, Instant.now());
        body.put("caseNumber", caseNumber);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/packages")
    public List<Map<String, Object>> packages(Principal principal) {
        return packages.list(principal.getName());
    }

    /** Recorded as PACKAGE_DOWNLOADED; {@code 410 package_expired} after the download window. */
    @GetMapping("/packages/{packageId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String packageId, Principal principal) {
        EvidencePackage p = packages.download(packageId, principal.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + p.getPackageId() + ".zip\"")
                .header("X-Package-SHA256", p.getSha256())
                .header("X-Manifest-SHA256", p.getManifestSha256())
                .header("X-Signing-Key-Id", p.getSigningKeyId())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(p.getContent());
    }
}
