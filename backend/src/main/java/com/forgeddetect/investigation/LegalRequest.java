package com.forgeddetect.investigation;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase 28: a legal request from an authority (court order, police notice) naming the cases one
 * investigator may receive. Filed by a moderator, approved by a senior moderator who did not file it;
 * access ends at {@link #expiresAt} or when a senior revokes it.
 */
@Entity
@Table(name = "investigation_legal_requests")
public class LegalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code LRQ-XXXX-XXXX}. */
    @Column(nullable = false, unique = true, length = 16)
    private String requestNumber;

    /** Who issued the order, e.g. "Cyber Crime Cell, Mumbai Police". */
    @Column(nullable = false, length = 160)
    private String authority;

    /** The authority's own reference: case diary, order or notice number. */
    @Column(nullable = false, length = 128)
    private String orderReference;

    /** Username of the INVESTIGATOR account the request grants access to. */
    @Column(nullable = false, length = 64)
    private String investigator;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "investigation_legal_request_cases", joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "investigation_id", nullable = false)
    private Set<Long> caseIds = new TreeSet<>();

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LegalRequestStatus status = LegalRequestStatus.PENDING;

    @Column(length = 512)
    private String note;

    @Column(nullable = false, length = 64)
    private String filedBy;

    @Column(nullable = false)
    private Instant filedAt;

    @Column(length = 64)
    private String decidedBy;

    private Instant decidedAt;

    @Column(length = 512)
    private String decisionNote;

    @Column(length = 64)
    private String revokedBy;

    private Instant revokedAt;

    @Column(length = 512)
    private String revokeReason;

    protected LegalRequest() {}

    public LegalRequest(String requestNumber, String authority, String orderReference, String investigator, Set<Long> caseIds,
            Instant expiresAt, String note, String filedBy, Instant filedAt) {
        this.requestNumber = requestNumber;
        this.authority = authority;
        this.orderReference = orderReference;
        this.investigator = investigator;
        this.caseIds = new TreeSet<>(caseIds);
        this.expiresAt = expiresAt;
        this.note = note;
        this.filedBy = filedBy;
        this.filedAt = filedAt;
    }

    void decide(boolean approve, String actor, Instant at, String note) {
        status = approve ? LegalRequestStatus.APPROVED : LegalRequestStatus.REJECTED;
        decidedBy = actor;
        decidedAt = at;
        decisionNote = note;
    }

    void revoke(String actor, Instant at, String reason) {
        status = LegalRequestStatus.REVOKED;
        revokedBy = actor;
        revokedAt = at;
        revokeReason = reason;
    }

    /** Approved and not yet expired: the only state that grants access. */
    public boolean grantsAccess(Instant now) {
        return status == LegalRequestStatus.APPROVED && now.isBefore(expiresAt);
    }

    /** PENDING, APPROVED, EXPIRED, REJECTED or REVOKED. */
    public String effectiveStatus(Instant now) {
        return status == LegalRequestStatus.APPROVED && !now.isBefore(expiresAt) ? "EXPIRED" : status.name();
    }

    public Long getId() {
        return id;
    }

    public String getRequestNumber() {
        return requestNumber;
    }

    public String getAuthority() {
        return authority;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public String getInvestigator() {
        return investigator;
    }

    public Set<Long> getCaseIds() {
        return caseIds;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public LegalRequestStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getFiledBy() {
        return filedBy;
    }

    public Instant getFiledAt() {
        return filedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }
}
