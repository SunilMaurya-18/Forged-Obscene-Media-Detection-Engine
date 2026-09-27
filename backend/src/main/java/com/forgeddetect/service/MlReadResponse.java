package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response from ML service {@code POST /provenance/read}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlReadResponse(
        String phash,
        @JsonProperty("content_hash") String contentHash,
        MlProvenanceCheck provenance) {}
