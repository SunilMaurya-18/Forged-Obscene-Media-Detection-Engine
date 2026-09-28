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

/**
 * Phase 23: internal, append-only log of who did what to an item (assignments, escalations, reveals,
 * second reviews, appeal decisions). Unlike the public provenance trail it names the moderator, so it
 * is only readable by moderators.
 */
@Entity
@Table(name = "moderation_audit_events", indexes = {
    @Index(name = "idx_moderation_audit_analysis", columnList = "analysisId"),
    @Index(name = "idx_moderation_audit_actor", columnList = "actor")
})
public class ModerationAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 64)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ModerationAction action;

    private Long analysisId;

    private Long appealId;

    @Column(length = 512)
    private String detail;

    protected ModerationAuditEvent() {}

    public ModerationAuditEvent(Instant occurredAt, String actor, ModerationAction action, Long analysisId, Long appealId, String detail) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.analysisId = analysisId;
        this.appealId = appealId;
        this.detail = detail != null && detail.length() > 512 ? detail.substring(0, 512) : detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public ModerationAction getAction() {
        return action;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public Long getAppealId() {
        return appealId;
    }

    public String getDetail() {
        return detail;
    }
}
