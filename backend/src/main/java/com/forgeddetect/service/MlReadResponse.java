package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Response from ML service {@code POST /provenance/read}. {@code c2pa} is null when the file has no Content Credentials. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlReadResponse(
        String phash,
        @JsonProperty("content_hash") String contentHash,
        MlProvenanceCheck provenance,
        JsonNode c2pa) {}
