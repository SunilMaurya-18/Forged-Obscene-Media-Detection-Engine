package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ProvenanceService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final AnalysisService analysisService;

    public AnalyzeController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** {@code jurisdiction} (optional, e.g. IN, US, EU, GB, AU): which rules govern this upload's appeal clock. */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "jurisdiction", required = false) String jurisdiction) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }

        AnalysisService.AnalyzeOutcome outcome;
        try {
            outcome = analysisService.analyze(file.getBytes(), file.getOriginalFilename(), file.getContentType(), null, jurisdiction);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        AnalysisResult result = outcome.result();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.getId());
        body.put("phash", result.getPhash());
        body.put("forgeryScore", result.getForgeryScore());
        body.put("explicitScore", result.getExplicitScore());
        body.put("generationFamily", result.getGenerationFamily());
        body.put("familyConfidence", result.getFamilyConfidence());
        body.put("decision", result.getDecision().name());
        body.put("jurisdiction", result.getJurisdiction());
        body.put("createdAt", result.getCreatedAt().toString());
        body.put("heatmap", outcome.heatmap() != null ? outcome.heatmap() : "");
        MediaViews.put(body, result);
        putProvenance(body, outcome);
        putNotice(body, outcome);
        return ResponseEntity.ok(body);
    }

    /** Provenance ID, labelled copy (returned to the caller, never stored) and upload lineage. */
    static void putProvenance(Map<String, Object> body, AnalysisService.AnalyzeOutcome outcome) {
        ProvenanceService.LabelledImage labelled = outcome.labelled();
        body.put("provenanceId", outcome.result().getProvenanceId());
        body.put("contentHash", outcome.result().getContentHash());
        body.put("labelledImage", labelled != null ? labelled.base64() : null);
        body.put("labelledMimeType", labelled != null ? labelled.mimeType() : null);
        body.put("labelledContentCredentials", labelled != null ? labelled.c2pa() : null);
        body.put("embeddedSource", outcome.embeddedSource());
    }

    /** Statement-of-reasons notice: the uploader needs the number + PIN to check or appeal the decision. */
    static void putNotice(Map<String, Object> body, AnalysisService.AnalyzeOutcome outcome) {
        if (outcome.notice() == null) {
            return;
        }
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("noticeNumber", outcome.notice().noticeNumber());
        notice.put("pin", outcome.notice().pin());
        notice.put("appealable", outcome.result().getDecision() == Decision.AUTO_FLAG);
        notice.put("statusUrl", "/api/appeals/" + outcome.notice().noticeNumber() + "/status");
        body.put("notice", notice);
    }
}
