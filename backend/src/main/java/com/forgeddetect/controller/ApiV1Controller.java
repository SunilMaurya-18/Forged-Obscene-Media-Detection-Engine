package com.forgeddetect.controller;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.security.ApiClientPrincipal;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ApiKeyService;
import com.forgeddetect.service.MediaTooLargeException;
import com.forgeddetect.service.MlRejectedException;
import com.forgeddetect.service.ProvenanceService;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Phase 16: versioned third-party API. Authenticated and rate limited by {@code ApiKeyAuthFilter}. */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {

    private static final Logger log = LoggerFactory.getLogger(ApiV1Controller.class);
    private static final Set<String> INCLUDES = Set.of("heatmap", "labelled");

    private final AnalysisService analysisService;
    private final ApiKeyService keys;

    public ApiV1Controller(AnalysisService analysisService, ApiKeyService keys) {
        this.analysisService = analysisService;
        this.keys = keys;
    }

    /**
     * Scores one image or video and returns the routing decision. Base64 payloads are opt-in via
     * {@code include=heatmap,labelled} to keep the default response small.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> analyze(
            @AuthenticationPrincipal ApiClientPrincipal client,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "include", required = false) String include)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "file_required", "Send the image as multipart field 'file'.");
        }
        Set<String> extras = include == null ? Set.of() : Arrays.stream(include.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (!INCLUDES.containsAll(extras)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_include", "include accepts: heatmap, labelled");
        }

        AnalysisService.AnalyzeOutcome outcome;
        try {
            outcome = analysisService.analyze(
                    file.getBytes(), file.getOriginalFilename(), file.getContentType(), client.id());
        } catch (MediaTooLargeException ex) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Images must be at most 25 MB and videos at most 50 MB.");
        } catch (MlRejectedException ex) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "unreadable_media",
                    "The file is not a readable JPEG/PNG/WebP image or MP4/WebM/MOV video. Retrying the same file won't help.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            log.warn("v1 analyze failed for client {}: {}", client.id(), ex.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "analysis_unavailable",
                    "The media could not be analysed. Check it is a valid JPEG/PNG/WebP image or MP4/WebM/MOV video and retry.");
        }
        AnalysisResult result = outcome.result();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.getId());
        body.put("decision", result.getDecision().name());
        body.put("forgeryScore", result.getForgeryScore());
        body.put("explicitScore", result.getExplicitScore());
        body.put("generationFamily", result.getGenerationFamily());
        body.put("phash", result.getPhash());
        body.put("contentHash", result.getContentHash());
        body.put("provenanceId", result.getProvenanceId());
        body.put("verifyUrl", result.getProvenanceId() != null ? "/api/verify/" + result.getProvenanceId() : null);
        body.put("reuploadOf", reuploadView(outcome.embeddedSource()));
        body.put("createdAt", result.getCreatedAt().toString());
        MediaViews.put(body, result);
        AnalyzeController.putNotice(body, outcome);
        if (extras.contains("heatmap")) {
            body.put("heatmap", outcome.heatmap() != null ? outcome.heatmap() : "");
        }
        if (extras.contains("labelled")) {
            ProvenanceService.LabelledImage labelled = outcome.labelled();
            body.put("labelledImage", labelled != null ? labelled.base64() : null);
            body.put("labelledMimeType", labelled != null ? labelled.mimeType() : null);
            body.put("labelledContentCredentials", labelled != null ? labelled.c2pa() : null);
        }
        return ResponseEntity.ok(body);
    }

    /** Usage for the calling key. Not metered, so it works even while throttled. */
    @GetMapping("/usage")
    public Map<String, Object> usage(@AuthenticationPrincipal ApiClientPrincipal client) {
        return keys.usage(client.id());
    }

    /** Same lineage the public verify page shows, minus internal row IDs that belong to other clients. */
    private static Map<String, Object> reuploadView(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>(source);
        view.remove("analysisId");
        return view;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
