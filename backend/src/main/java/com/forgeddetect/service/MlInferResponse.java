package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response payload from ML service {@code POST /infer}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlInferResponse {

    @JsonProperty("forgery_score")
    private double forgeryScore;

    @JsonProperty("explicit_score")
    private double explicitScore;

    private String heatmap;
    private String phash;

    @JsonProperty("content_hash")
    private String contentHash;

    /** Fresh ID issued by the ML service for this analysis. */
    @JsonProperty("provenance_id")
    private String provenanceId;

    /** Manifest already embedded in the upload; null for unlabelled media. */
    private MlProvenanceCheck provenance;

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getProvenanceId() {
        return provenanceId;
    }

    public void setProvenanceId(String provenanceId) {
        this.provenanceId = provenanceId;
    }

    public MlProvenanceCheck getProvenance() {
        return provenance;
    }

    public void setProvenance(MlProvenanceCheck provenance) {
        this.provenance = provenance;
    }

    public double getForgeryScore() {
        return forgeryScore;
    }

    public void setForgeryScore(double forgeryScore) {
        this.forgeryScore = forgeryScore;
    }

    public double getExplicitScore() {
        return explicitScore;
    }

    public void setExplicitScore(double explicitScore) {
        this.explicitScore = explicitScore;
    }

    public String getHeatmap() {
        return heatmap;
    }

    public void setHeatmap(String heatmap) {
        this.heatmap = heatmap;
    }

    public String getPhash() {
        return phash;
    }

    public void setPhash(String phash) {
        this.phash = phash;
    }
}
