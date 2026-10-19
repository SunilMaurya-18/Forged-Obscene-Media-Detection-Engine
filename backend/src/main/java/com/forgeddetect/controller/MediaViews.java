package com.forgeddetect.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import java.util.Map;

/**
 * Phase 23 response fields shared by every view of an analysis: media type, frame timeline, C2PA
 * credentials. Values are plain Maps/Lists (not Jackson {@code JsonNode}) so Boot 4's Jackson 3 HTTP
 * converter can serialize them.
 */
final class MediaViews {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MediaViews() {}

    static void put(Map<String, Object> body, AnalysisResult result) {
        body.put("mediaType", result.isVideo() ? "VIDEO" : "IMAGE");
        body.put("video", result.isVideo() ? parse(result.getFrameTimeline()) : null);
        body.put("contentCredentials", parse(result.getC2paManifest()));
    }

    static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
