package com.forgeddetect.service;

import com.forgeddetect.observability.FmdMetrics;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Calls the internal ML microservice: {@code POST /infer}, the Phase 14 provenance routes
 * {@code POST /provenance/embed} and {@code POST /provenance/read}, and the Phase 15
 * {@code POST /forensics/panels}.
 */
@Service
public class MlClientService {

    private final WebClient webClient;
    private final FmdMetrics metrics;
    private final Duration timeout;

    public MlClientService(
            WebClient.Builder webClientBuilder,
            @Value("${ml.service.url}") String mlServiceUrl,
            @Value("${ml.service.timeout:PT60S}") Duration timeout,
            FmdMetrics metrics) {
        this.webClient = webClientBuilder.baseUrl(mlServiceUrl).build();
        this.timeout = timeout;
        this.metrics = metrics;
    }

    public MlInferResponse infer(byte[] imageBytes, String filename, String contentType) {
        return post("/infer", imageBytes, filename, contentType, Map.of(), MlInferResponse.class);
    }

    /** Phase 23: frame-by-frame scoring; the ML service decodes in memory and keeps no frames. */
    public MlInferResponse inferVideo(byte[] videoBytes, String filename, String contentType, double fps, int topK) {
        return post("/infer-video", videoBytes, filename, contentType,
                Map.of("fps", String.valueOf(fps), "top_k", String.valueOf(topK)), MlInferResponse.class);
    }

    /** Returns a copy of the image with a signed provenance manifest embedded. */
    public MlLabelResponse label(byte[] imageBytes, String filename, String contentType, Map<String, String> fields) {
        return post("/provenance/embed", imageBytes, filename, contentType, fields, MlLabelResponse.class);
    }

    /** Reads back an embedded manifest without running the models. */
    public MlReadResponse readProvenance(byte[] imageBytes, String filename, String contentType) {
        return post("/provenance/read", imageBytes, filename, contentType, Map.of(), MlReadResponse.class);
    }

    /** Full-resolution Grad-CAM / FFT / ELA panels for a report; nothing is kept by the ML service. */
    public MlPanelsResponse forensicPanels(byte[] imageBytes, String filename, String contentType) {
        return post("/forensics/panels", imageBytes, filename, contentType, Map.of(), MlPanelsResponse.class);
    }

    private <T> T post(
            String path,
            byte[] imageBytes,
            String filename,
            String contentType,
            Map<String, String> fields,
            Class<T> responseType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("image bytes must not be empty");
        }
        String name = (filename == null || filename.isBlank()) ? "upload.jpg" : filename;
        MediaType partType = MediaType.APPLICATION_OCTET_STREAM;
        if (contentType != null && !contentType.isBlank()) {
            try {
                partType = MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {
                // keep octet-stream
            }
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(partType);
        fields.forEach(builder::part);

        try {
            return webClient
                    .post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(responseType)
                    .timeout(timeout)
                    .block();
        } catch (WebClientResponseException ex) {
            String message = "ML service " + path + " failed: HTTP " + ex.getStatusCode().value();
            if (ex.getStatusCode().is4xxClientError()) {
                metrics.mlError(path, "http_4xx");
                throw new MlRejectedException(message, ex);
            }
            metrics.mlError(path, "http_5xx");
            throw new IllegalStateException(message, ex);
        } catch (Exception ex) {
            metrics.mlError(path, hasCause(ex, TimeoutException.class) ? "timeout" : "unavailable");
            throw new IllegalStateException("ML service " + path + " call failed: " + ex.getMessage(), ex);
        }
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
