package com.forgeddetect.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A provenance manifest read back from uploaded media by the ML service, plus its checks.
 * {@code status} is VERIFIED, CONTENT_MODIFIED or INVALID_SIGNATURE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MlProvenanceCheck {

    private String spec;
    private String provenanceId;
    private String issuer;
    private String contentHash;
    private String analyzedAt;
    private Double forgeryScore;
    private Double explicitScore;
    private String decision;
    private String derivedFrom;
    private boolean signatureValid;
    private boolean contentMatch;
    private String status;

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getProvenanceId() {
        return provenanceId;
    }

    public void setProvenanceId(String provenanceId) {
        this.provenanceId = provenanceId;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(String analyzedAt) {
        this.analyzedAt = analyzedAt;
    }

    public Double getForgeryScore() {
        return forgeryScore;
    }

    public void setForgeryScore(Double forgeryScore) {
        this.forgeryScore = forgeryScore;
    }

    public Double getExplicitScore() {
        return explicitScore;
    }

    public void setExplicitScore(Double explicitScore) {
        this.explicitScore = explicitScore;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getDerivedFrom() {
        return derivedFrom;
    }

    public void setDerivedFrom(String derivedFrom) {
        this.derivedFrom = derivedFrom;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public void setSignatureValid(boolean signatureValid) {
        this.signatureValid = signatureValid;
    }

    public boolean isContentMatch() {
        return contentMatch;
    }

    public void setContentMatch(boolean contentMatch) {
        this.contentMatch = contentMatch;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
