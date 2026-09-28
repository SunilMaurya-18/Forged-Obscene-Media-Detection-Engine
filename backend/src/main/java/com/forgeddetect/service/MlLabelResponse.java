package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Response from ML service {@code POST /provenance/embed}; {@code c2pa} describes the manifest written into the copy. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlLabelResponse(
        String image,
        @JsonProperty("mime_type") String mimeType,
        MlProvenanceCheck manifest,
        JsonNode c2pa) {}
