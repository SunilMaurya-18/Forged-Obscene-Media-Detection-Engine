package com.forgeddetect.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** A sighting matched to a case, and how: {@code PROVENANCE} (distance 0) or {@code PHASH} (Hamming distance). */
@Entity
@Table(name = "sighting_links", uniqueConstraints = @UniqueConstraint(columnNames = {"investigationId", "sightingId"}))
public class SightingLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long investigationId;

    @Column(nullable = false)
    private Long sightingId;

    /** The case analysis the sighting matched. */
    @Column(nullable = false)
    private Long analysisId;

    @Column(nullable = false, length = 16)
    private String via;

    private int distance;

    @Column(nullable = false)
    private Instant linkedAt;

    protected SightingLink() {}

    public SightingLink(Long investigationId, Long sightingId, Long analysisId, String via, int distance, Instant linkedAt) {
        this.investigationId = investigationId;
        this.sightingId = sightingId;
        this.analysisId = analysisId;
        this.via = via;
        this.distance = distance;
        this.linkedAt = linkedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public Long getSightingId() {
        return sightingId;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public String getVia() {
        return via;
    }

    public int getDistance() {
        return distance;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
