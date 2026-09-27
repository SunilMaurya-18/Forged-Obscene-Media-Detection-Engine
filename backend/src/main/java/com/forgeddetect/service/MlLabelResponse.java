package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response from ML service {@code POST /provenance/embed}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlLabelResponse(
        String image,
        @JsonProperty("mime_type") String mimeType,
        MlProvenanceCheck manifest) {}
