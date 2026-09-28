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

    /** The decision engine's original routing, kept when a moderator changes {@link #decision}. Null for pre-Phase-22 rows. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Decision modelDecision;

    /** Last time a moderator set the decision; null while only the model has decided. */
    private Instant reviewedAt;

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

    /**
     * Coarse numeric forensic signature (JSON: 14x14 Grad-CAM, 16x16 ELA, 32x32 FFT magnitude grids).
     * Too low-resolution to reconstruct the image; used to draw report panels without keeping media.
     */
    @Column(length = 16000)
    private String forensicSignature;

    /** API client that submitted this via {@code /api/v1/analyze}; null for dashboard and feed uploads. */
    private Long apiClientId;

    /** gan / diffusion / unknown from the family head; null for rows analysed before Phase 17. */
    @Column(length = 16)
    private String generationFamily;

    private Double familyConfidence;

    /** Jurisdiction whose takedown rules apply (request parameter or platform default; never geo-IP). */
    @Column(length = 16)
    private String jurisdiction;

    /** Label of the rule that set {@link #slaDeadline}, frozen at complaint time so later rule edits don't rewrite history. */
    @Column(length = 64)
    private String slaRuleLabel;

    /** Latest signed evidentiary report (Phase 20). The PDF itself is not stored. */
    @Column(length = 64)
    private String reportId;

    private Instant reportSignedAt;

    /** SHA-256 (hex) of {@link #reportCanonical}. */
    @Column(length = 64)
    private String reportContentHash;

    /** Base64 Ed25519 signature over the content hash. */
    @Column(length = 128)
    private String reportSignature;

    @Column(length = 32)
    private String reportSigningKeyId;

    /** The exact field snapshot that was signed, so verification can name what changed. */
    @Column(length = 4000)
    private String reportCanonical;

    /** Phase 24: base64 RFC 3161 timestamp token over the report signature (null when no TSA is configured). */
    @Column(length = 16000)
    private String reportTimestampToken;

    private Instant reportTimestampAt;

    @Column(length = 255)
    private String reportTimestampAuthority;

    /** IMAGE or VIDEO (Phase 23); null for rows analysed before video support, which are all images. */
    @Column(length = 8)
    private String mediaType;

    /**
     * Video only: per-frame scores and the aggregation used for routing, as compact JSON
     * ({@code frames: [[t, forgery, explicit], ...]}). Scores only; no frame is kept.
     */
    @Column(length = 8000)
    private String frameTimeline;

    /** Summary of C2PA Content Credentials the upload carried (signer, generator, actions), as JSON. */
    @Column(length = 4000)
    private String c2paManifest;

    /** Moderator whose decision is current; null while only the model has decided. */
    @Column(length = 64)
    private String decidedBy;

    @Column(length = 64)
    private String assignedTo;

    @Column(length = 64)
    private String assignedTeam;

    private Instant assignedAt;

    /** Team a senior routed the item to; only that team (or a senior) can claim or decide it while unclaimed. */
    @Column(length = 64)
    private String routedTeam;

    /** Escalated to a senior moderator: only SENIOR_MODERATOR can decide it until then. */
    private Instant seniorEscalatedAt;

    @Column(length = 64)
    private String seniorEscalatedBy;

    @Column(length = 500)
    private String seniorEscalationReason;

    /** A senior acted on the escalation; the item leaves the senior queue but the escalation stays on record. */
    private Instant seniorResolvedAt;

    @Column(length = 64)
    private String seniorResolvedBy;

    /** Proposed change away from AUTO_FLAG, waiting for a second moderator to confirm it. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Decision pendingDecision;

    @Column(length = 64)
    private String pendingProposedBy;

    private Instant pendingProposedAt;

    @Column(length = 500)
    private String pendingReason;

    /** Upload case number given to the uploader, used to appeal a takedown. */
    @Column(unique = true, length = 16)
    private String noticeNumber;

    @Column(length = 80)
    private String noticePinHash;

    /** Denormalised from {@link Appeal} for queue views and the signed report: OPEN / UPHELD / GRANTED. */
    @Column(length = 16)
    private String appealStatus;

    private Instant appealDecidedAt;

    public boolean isVideo() {
        return "VIDEO".equals(mediaType);
    }

    public boolean isSeniorEscalated() {
        return seniorEscalatedAt != null && seniorResolvedAt == null;
    }

    public String getRoutedTeam() {
        return routedTeam;
    }

    public void setRoutedTeam(String routedTeam) {
        this.routedTeam = routedTeam;
    }

    public Instant getSeniorResolvedAt() {
        return seniorResolvedAt;
    }

    public void setSeniorResolvedAt(Instant seniorResolvedAt) {
        this.seniorResolvedAt = seniorResolvedAt;
    }

    public String getSeniorResolvedBy() {
        return seniorResolvedBy;
    }

    public void setSeniorResolvedBy(String seniorResolvedBy) {
        this.seniorResolvedBy = seniorResolvedBy;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getFrameTimeline() {
        return frameTimeline;
    }

    public void setFrameTimeline(String frameTimeline) {
        this.frameTimeline = frameTimeline;
    }

    public String getC2paManifest() {
        return c2paManifest;
    }

    public void setC2paManifest(String c2paManifest) {
        this.c2paManifest = c2paManifest;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getAssignedTeam() {
        return assignedTeam;
    }

    public void setAssignedTeam(String assignedTeam) {
        this.assignedTeam = assignedTeam;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getSeniorEscalatedAt() {
        return seniorEscalatedAt;
    }

    public void setSeniorEscalatedAt(Instant seniorEscalatedAt) {
        this.seniorEscalatedAt = seniorEscalatedAt;
    }

    public String getSeniorEscalatedBy() {
        return seniorEscalatedBy;
    }

    public void setSeniorEscalatedBy(String seniorEscalatedBy) {
        this.seniorEscalatedBy = seniorEscalatedBy;
    }

    public String getSeniorEscalationReason() {
        return seniorEscalationReason;
    }

    public void setSeniorEscalationReason(String seniorEscalationReason) {
        this.seniorEscalationReason = seniorEscalationReason;
    }

    public Decision getPendingDecision() {
        return pendingDecision;
    }

    public void setPendingDecision(Decision pendingDecision) {
        this.pendingDecision = pendingDecision;
    }

    public String getPendingProposedBy() {
        return pendingProposedBy;
    }

    public void setPendingProposedBy(String pendingProposedBy) {
        this.pendingProposedBy = pendingProposedBy;
    }

    public Instant getPendingProposedAt() {
        return pendingProposedAt;
    }

    public void setPendingProposedAt(Instant pendingProposedAt) {
        this.pendingProposedAt = pendingProposedAt;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void setPendingReason(String pendingReason) {
        this.pendingReason = pendingReason;
    }

    public void clearPending() {
        pendingDecision = null;
        pendingProposedBy = null;
        pendingProposedAt = null;
        pendingReason = null;
    }

    public String getNoticeNumber() {
        return noticeNumber;
    }

    public void setNoticeNumber(String noticeNumber) {
        this.noticeNumber = noticeNumber;
    }

    public String getNoticePinHash() {
        return noticePinHash;
    }

    public void setNoticePinHash(String noticePinHash) {
        this.noticePinHash = noticePinHash;
    }

    public String getAppealStatus() {
        return appealStatus;
    }

    public void setAppealStatus(String appealStatus) {
        this.appealStatus = appealStatus;
    }

    public Instant getAppealDecidedAt() {
        return appealDecidedAt;
    }

    public void setAppealDecidedAt(Instant appealDecidedAt) {
        this.appealDecidedAt = appealDecidedAt;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    public String getSlaRuleLabel() {
        return slaRuleLabel;
    }

    public void setSlaRuleLabel(String slaRuleLabel) {
        this.slaRuleLabel = slaRuleLabel;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public Instant getReportSignedAt() {
        return reportSignedAt;
    }

    public void setReportSignedAt(Instant reportSignedAt) {
        this.reportSignedAt = reportSignedAt;
    }

    public String getReportContentHash() {
        return reportContentHash;
    }

    public void setReportContentHash(String reportContentHash) {
        this.reportContentHash = reportContentHash;
    }

    public String getReportSignature() {
        return reportSignature;
    }

    public void setReportSignature(String reportSignature) {
        this.reportSignature = reportSignature;
    }

    public String getReportSigningKeyId() {
        return reportSigningKeyId;
    }

    public void setReportSigningKeyId(String reportSigningKeyId) {
        this.reportSigningKeyId = reportSigningKeyId;
    }

    public String getReportTimestampToken() {
        return reportTimestampToken;
    }

    public void setReportTimestampToken(String reportTimestampToken) {
        this.reportTimestampToken = reportTimestampToken;
    }

    public Instant getReportTimestampAt() {
        return reportTimestampAt;
    }

    public void setReportTimestampAt(Instant reportTimestampAt) {
        this.reportTimestampAt = reportTimestampAt;
    }

    public String getReportTimestampAuthority() {
        return reportTimestampAuthority;
    }

    public void setReportTimestampAuthority(String reportTimestampAuthority) {
        this.reportTimestampAuthority = reportTimestampAuthority;
    }

    public String getReportCanonical() {
        return reportCanonical;
    }

    public void setReportCanonical(String reportCanonical) {
        this.reportCanonical = reportCanonical;
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

    public Long getApiClientId() {
        return apiClientId;
    }

    public void setApiClientId(Long apiClientId) {
        this.apiClientId = apiClientId;
    }

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

    public Decision getModelDecision() {
        return modelDecision;
    }

    public void setModelDecision(Decision modelDecision) {
        this.modelDecision = modelDecision;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
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

    public String getForensicSignature() {
        return forensicSignature;
    }

    public void setForensicSignature(String forensicSignature) {
        this.forensicSignature = forensicSignature;
    }

    public boolean isComplianceOpen() {
        return complaintType != null && resolvedAt == null;
    }
}
