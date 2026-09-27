package com.forgeddetect.model;

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

/** Append-only audit trail entry for one provenance ID. Metadata only. */
@Entity
@Table(name = "provenance_events", indexes = @Index(name = "idx_provenance_events_pid", columnList = "provenanceId"))
public class ProvenanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String provenanceId;

    private Long analysisId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProvenanceEventType type;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 32)
    private String actor;

    @Column(length = 512)
    private String detail;

    protected ProvenanceEvent() {}

    public ProvenanceEvent(
            String provenanceId, Long analysisId, ProvenanceEventType type, Instant occurredAt, String actor, String detail) {
        this.provenanceId = provenanceId;
        this.analysisId = analysisId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public String getProvenanceId() {
        return provenanceId;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public ProvenanceEventType getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }
}
