package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response from ML service {@code POST /forensics/panels}: base64 PNG panels plus hashes of the input. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlPanelsResponse(
        String heatmap,
        String fft,
        String ela,
        String phash,
        @JsonProperty("content_hash") String contentHash) {}
