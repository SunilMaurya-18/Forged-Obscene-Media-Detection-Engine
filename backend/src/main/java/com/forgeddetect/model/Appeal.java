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

/**
 * Phase 23: an uploader contesting a takedown (EU DSA Art. 20 style internal complaint handling).
 * Filed with the upload's case number and PIN; timed by its own SLA clock from the
 * {@code (jurisdiction, APPEAL)} rule; decided by a moderator other than the one who made the
 * contested decision. Granting it proposes reinstatement, which still needs a second review.
 */
@Entity
@Table(name = "appeals")
public class Appeal {

    public enum Status { OPEN, UPHELD, GRANTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The upload's case number ({@code UPL-XXXX-XXXX}); one appeal per upload. */
    @Column(nullable = false, unique = true, length = 16)
    private String caseNumber;

    @Column(nullable = false, unique = true)
    private Long analysisId;

    @Column(nullable = false, length = 1000)
    private String statement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Decision contestedDecision;

    /** Moderator who made the contested decision; "system" when the model auto-flagged it. */
    @Column(nullable = false, length = 64)
    private String contestedBy;

    @Column(nullable = false)
    private Instant filedAt;

    @Column(nullable = false)
    private Instant slaDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SlaStatus slaStatus;

    @Column(length = 64)
    private String slaRuleLabel;

    private Instant escalatedAt;

    private Instant decidedAt;

    @Column(length = 64)
    private String decidedBy;

    @Column(length = 1000)
    private String outcomeReason;

    /** Set when a granted appeal's reinstatement passed second review and the content was restored. */
    private Instant reinstatedAt;

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public Long getId() {
        return id;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public void setCaseNumber(String caseNumber) {
        this.caseNumber = caseNumber;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(Long analysisId) {
        this.analysisId = analysisId;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Decision getContestedDecision() {
        return contestedDecision;
    }

    public void setContestedDecision(Decision contestedDecision) {
        this.contestedDecision = contestedDecision;
    }

    public String getContestedBy() {
        return contestedBy;
    }

    public void setContestedBy(String contestedBy) {
        this.contestedBy = contestedBy;
    }

    public Instant getFiledAt() {
        return filedAt;
    }

    public void setFiledAt(Instant filedAt) {
        this.filedAt = filedAt;
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

    public String getSlaRuleLabel() {
        return slaRuleLabel;
    }

    public void setSlaRuleLabel(String slaRuleLabel) {
        this.slaRuleLabel = slaRuleLabel;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getOutcomeReason() {
        return outcomeReason;
    }

    public void setOutcomeReason(String outcomeReason) {
        this.outcomeReason = outcomeReason;
    }

    public Instant getReinstatedAt() {
        return reinstatedAt;
    }

    public void setReinstatedAt(Instant reinstatedAt) {
        this.reinstatedAt = reinstatedAt;
    }
}
