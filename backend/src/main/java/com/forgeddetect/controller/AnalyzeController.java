package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ProvenanceService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> analyze(@RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }

        AnalysisService.AnalyzeOutcome outcome =
                analysisService.analyze(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        AnalysisResult result = outcome.result();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.getId());
        body.put("phash", result.getPhash());
        body.put("forgeryScore", result.getForgeryScore());
        body.put("explicitScore", result.getExplicitScore());
        body.put("generationFamily", result.getGenerationFamily());
        body.put("familyConfidence", result.getFamilyConfidence());
        body.put("decision", result.getDecision().name());
        body.put("createdAt", result.getCreatedAt().toString());
        body.put("heatmap", outcome.heatmap() != null ? outcome.heatmap() : "");
        putProvenance(body, outcome);
        return ResponseEntity.ok(body);
    }

    /** Provenance ID, labelled copy (returned to the caller, never stored) and upload lineage. */
    static void putProvenance(Map<String, Object> body, AnalysisService.AnalyzeOutcome outcome) {
        ProvenanceService.LabelledImage labelled = outcome.labelled();
        body.put("provenanceId", outcome.result().getProvenanceId());
        body.put("contentHash", outcome.result().getContentHash());
        body.put("labelledImage", labelled != null ? labelled.base64() : null);
        body.put("labelledMimeType", labelled != null ? labelled.mimeType() : null);
        body.put("embeddedSource", outcome.embeddedSource());
    }
}
