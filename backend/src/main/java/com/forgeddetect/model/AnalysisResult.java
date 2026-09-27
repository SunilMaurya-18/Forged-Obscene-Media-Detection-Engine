package com.forgeddetect.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Persisted analysis metadata only — never raw media bytes.
 */
@Entity
@Table(name = "analysis_results")
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String phash;

    @Column(nullable = false)
    private double forgeryScore;

    @Column(nullable = false)
    private double explicitScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Decision decision;

    /** Microsecond precision so the timestamp written into the manifest matches what Postgres stores. */
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    /** Null for ordinary uploads; set when the item is the subject of a takedown complaint. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ComplaintType complaintType;

    @Column(length = 128)
    private String complaintReference;

    private Instant complaintReceivedAt;

    private Instant slaDeadline;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private SlaStatus slaStatus;

    private Instant escalatedAt;

    /** When a moderator acted on the complaint; the SLA clock stops here. */
    private Instant resolvedAt;

    /** Traceable ID embedded in the labelled copy of the media. Null only for pre-Phase-14 rows. */
    @Column(unique = true, length = 64)
    private String provenanceId;

    /** Provenance ID of an earlier analysis this upload was matched to (re-upload lineage). */
    @Column(length = 64)
    private String sourceProvenanceId;

    /** SHA-256 of the decoded pixels, as written into the manifest. */
    @Column(length = 80)
    private String contentHash;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhash() {
        return phash;
    }

    public void setPhash(String phash) {
        this.phash = phash;
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

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public ComplaintType getComplaintType() {
        return complaintType;
    }

    public void setComplaintType(ComplaintType complaintType) {
        this.complaintType = complaintType;
    }

    public String getComplaintReference() {
        return complaintReference;
    }

    public void setComplaintReference(String complaintReference) {
        this.complaintReference = complaintReference;
    }

    public Instant getComplaintReceivedAt() {
        return complaintReceivedAt;
    }

    public void setComplaintReceivedAt(Instant complaintReceivedAt) {
        this.complaintReceivedAt = complaintReceivedAt;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }

    public void setSlaDeadline(Instant slaDeadline) {
        this.slaDeadline = slaDeadline;
    }

    public SlaStatus getSlaStatus() {
        return slaStatus;
    }

    public void setSlaStatus(SlaStatus slaStatus) {
        this.slaStatus = slaStatus;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getProvenanceId() {
        return provenanceId;
    }

    public void setProvenanceId(String provenanceId) {
        this.provenanceId = provenanceId;
    }

    public String getSourceProvenanceId() {
        return sourceProvenanceId;
    }

    public void setSourceProvenanceId(String sourceProvenanceId) {
        this.sourceProvenanceId = sourceProvenanceId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isComplianceOpen() {
        return complaintType != null && resolvedAt == null;
    }
}
