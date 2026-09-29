package com.forgeddetect.controller;

import com.forgeddetect.investigation.EvidencePackageService;
import com.forgeddetect.service.ProvenanceService;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Phase 14: public provenance lookup. Anyone holding labelled media (or its ID) can check its
 * authenticity and audit history. Phase 28: a ZIP upload is checked as an evidence package instead.
 * Nothing is stored by these endpoints.
 */
@RestController
@RequestMapping("/api/verify")
public class VerifyController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final ProvenanceService provenance;
    private final EvidencePackageService packages;

    public VerifyController(ProvenanceService provenance, EvidencePackageService packages) {
        this.provenance = provenance;
        this.packages = packages;
    }

    @GetMapping("/{provenanceId}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable String provenanceId) {
        if (!ID.matcher(provenanceId).matches()) {
            return notFound(provenanceId);
        }
        return provenance.trail(provenanceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> notFound(provenanceId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> byUpload(@RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        byte[] bytes = file.getBytes();
        if (EvidencePackageService.looksLikePackage(bytes)) {
            return ResponseEntity.ok(packages.verifyUpload(bytes));
        }
        return ResponseEntity.ok(provenance.verifyUpload(bytes, file.getOriginalFilename(), file.getContentType()));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Unknown provenance ID", "provenanceId", id));
    }
}
