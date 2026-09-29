package com.forgeddetect.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 27: a request to a partner platform to keep specific posts (and the media in them) under its
 * own legal hold. This system holds only the request and the platform's answer; every step is also
 * a custody event, so the record of who asked for what, and when, is part of the chain.
 */
@Entity
@Table(name = "investigation_legal_holds", indexes = @Index(name = "idx_legal_holds_case", columnList = "investigationId"))
public class LegalHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code HLD-XXXX-XXXX}. */
    @Column(nullable = false, unique = true, length = 16)
    private String holdNumber;

    @Column(nullable = false)
    private Long investigationId;

    @Column(nullable = false, length = 48)
    private String platform;

    /** The partner's opaque post IDs the hold covers, sorted, one per line. */
    @Column(nullable = false, length = 16000)
    private String postRefs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LegalHoldStatus status = LegalHoldStatus.REQUESTED;

    @Column(length = 512)
    private String note;

    @Column(nullable = false, length = 64)
    private String requestedBy;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(length = 64)
    private String acknowledgedBy;

    private Instant acknowledgedAt;

    /** The platform's own reference for the hold, e.g. a ticket number. */
    @Column(length = 128)
    private String partnerReference;

    @Column(length = 64)
    private String releasedBy;

    private Instant releasedAt;

    @Column(length = 512)
    private String releaseReason;

    protected LegalHold() {}

    public LegalHold(String holdNumber, Long investigationId, String platform, List<String> postRefs, String note,
            String requestedBy, Instant requestedAt) {
        this.holdNumber = holdNumber;
        this.investigationId = investigationId;
        this.platform = platform;
        this.postRefs = String.join("\n", postRefs);
        this.note = note;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
    }

    void acknowledge(String actor, Instant at, String reference) {
        status = LegalHoldStatus.ACKNOWLEDGED;
        acknowledgedBy = actor;
        acknowledgedAt = at;
        partnerReference = reference;
    }

    void release(String actor, Instant at, String reason) {
        status = LegalHoldStatus.RELEASED;
        releasedBy = actor;
        releasedAt = at;
        releaseReason = reason;
    }

    public List<String> postRefList() {
        return postRefs == null || postRefs.isEmpty() ? List.of() : Arrays.asList(postRefs.split("\n"));
    }

    public Long getId() {
        return id;
    }

    public String getHoldNumber() {
        return holdNumber;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public String getPlatform() {
        return platform;
    }

    public LegalHoldStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getPartnerReference() {
        return partnerReference;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }
}
