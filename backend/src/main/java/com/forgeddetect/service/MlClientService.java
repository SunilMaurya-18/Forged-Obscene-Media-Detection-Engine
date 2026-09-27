package com.forgeddetect.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Calls the internal ML microservice: {@code POST /infer}, and the Phase 14 provenance routes
 * {@code POST /provenance/embed} and {@code POST /provenance/read}.
 */
@Service
public class MlClientService {

    private final WebClient webClient;

    public MlClientService(
            WebClient.Builder webClientBuilder,
            @Value("${ml.service.url}") String mlServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(mlServiceUrl).build();
    }

    public MlInferResponse infer(byte[] imageBytes, String filename, String contentType) {
        return post("/infer", imageBytes, filename, contentType, Map.of(), MlInferResponse.class);
    }

    /** Returns a copy of the image with a signed provenance manifest embedded. */
    public MlLabelResponse label(byte[] imageBytes, String filename, String contentType, Map<String, String> fields) {
        return post("/provenance/embed", imageBytes, filename, contentType, fields, MlLabelResponse.class);
    }

    /** Reads back an embedded manifest without running the models. */
    public MlReadResponse readProvenance(byte[] imageBytes, String filename, String contentType) {
        return post("/provenance/read", imageBytes, filename, contentType, Map.of(), MlReadResponse.class);
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
                    .block();
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException(
                    "ML service " + path + " failed: HTTP " + ex.getStatusCode().value(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("ML service " + path + " call failed: " + ex.getMessage(), ex);
        }
    }
}
