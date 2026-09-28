package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

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

    /** Coarse forensic signature (CAM / ELA / FFT grids) for report panels. */
    private JsonNode forensics;

    /** gan / diffusion / unknown — best-effort attribution, only set when the image looks forged. */
    @JsonProperty("generation_family")
    private String generationFamily;

    @JsonProperty("family_confidence")
    private Double familyConfidence;

    /** "image" or "video" (Phase 23). */
    @JsonProperty("media_type")
    private String mediaType;

    /** Video only: [{index, t, forgery_score, explicit_score}, ...]. */
    private JsonNode timeline;

    /** Video only: {forgery: {max, topk_mean, k}, explicit: {...}, k}. */
    private JsonNode aggregate;

    /** Video only: sampling details (sampled, fps, truncated, duration_seconds, codec, ...). */
    private JsonNode frames;

    /** Video only: {index, t} of the highest-forgery frame. */
    private JsonNode peak;

    /** C2PA Content Credentials summary, or null if the upload had none. */
    private JsonNode c2pa;

    public boolean isVideo() {
        return "video".equalsIgnoreCase(mediaType);
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public JsonNode getTimeline() {
        return timeline;
    }

    public void setTimeline(JsonNode timeline) {
        this.timeline = timeline;
    }

    public JsonNode getAggregate() {
        return aggregate;
    }

    public void setAggregate(JsonNode aggregate) {
        this.aggregate = aggregate;
    }

    public JsonNode getFrames() {
        return frames;
    }

    public void setFrames(JsonNode frames) {
        this.frames = frames;
    }

    public JsonNode getPeak() {
        return peak;
    }

    public void setPeak(JsonNode peak) {
        this.peak = peak;
    }

    public JsonNode getC2pa() {
        return c2pa;
    }

    public void setC2pa(JsonNode c2pa) {
        this.c2pa = c2pa;
    }

    public String getGenerationFamily() {
        return generationFamily;
    }

    public void setGenerationFamily(String generationFamily) {
        this.generationFamily = generationFamily;
    }

    public Double getFamilyConfidence() {
        return familyConfidence;
    }

    public void setFamilyConfidence(Double familyConfidence) {
        this.familyConfidence = familyConfidence;
    }

    public JsonNode getForensics() {
        return forensics;
    }

    public void setForensics(JsonNode forensics) {
        this.forensics = forensics;
    }

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
