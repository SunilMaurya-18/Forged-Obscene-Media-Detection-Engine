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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Internal case log. Names moderators, so it is never part of the public provenance trail; sightings
 * are recorded here and not as provenance events for the same reason.
 *
 * <p>Phase 27: also the custody log. Each event is sealed into a hash chain ({@link CustodyChain})
 * when it is written, so the log is append-only in a way anyone can check.
 */
@Entity
@Table(
        name = "investigation_events",
        indexes = @Index(name = "idx_investigation_events_case", columnList = "investigationId"),
        uniqueConstraints = @UniqueConstraint(name = "uq_investigation_events_seq", columnNames = {"investigationId", "seq"}))
public class InvestigationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long investigationId;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 64)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvestigationEventType type;

    private Long analysisId;

    private Long sightingId;

    @Column(length = 512)
    private String detail;

    /** Position in the case's custody chain, from 1. */
    private Integer seq;

    /** {@link #hash} of the previous event, or {@link CustodyChain#GENESIS} for the first. */
    @Column(length = 64)
    private String prevHash;

    @Column(length = 64)
    private String hash;

    protected InvestigationEvent() {}

    public InvestigationEvent(
            Long investigationId, Instant occurredAt, String actor, InvestigationEventType type,
            Long analysisId, Long sightingId, String detail) {
        this.investigationId = investigationId;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.type = type;
        this.analysisId = analysisId;
        this.sightingId = sightingId;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public InvestigationEventType getType() {
        return type;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public Long getSightingId() {
        return sightingId;
    }

    public String getDetail() {
        return detail;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }

    void seal(int seq, String prevHash, String hash) {
        this.seq = seq;
        this.prevHash = prevHash;
        this.hash = hash;
    }
}
