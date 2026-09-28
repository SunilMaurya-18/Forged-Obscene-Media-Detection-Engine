package com.forgeddetect.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Phase 26: groups analyses and the sightings linked to them into one case. Moderator-only. */
@Entity
@Table(name = "investigations")
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code INV-XXXX-XXXX}. */
    @Column(nullable = false, unique = true, length = 16)
    private String caseNumber;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvestigationStatus status = InvestigationStatus.OPEN;

    @Column(nullable = false, length = 64)
    private String openedBy;

    @Column(nullable = false)
    private Instant openedAt;

    @Column(length = 64)
    private String closedBy;

    private Instant closedAt;

    @Column(length = 512)
    private String closeReason;

    protected Investigation() {}

    public Investigation(String caseNumber, String title, String openedBy, Instant openedAt) {
        this.caseNumber = caseNumber;
        this.title = title;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
    }

    public boolean isOpen() {
        return status == InvestigationStatus.OPEN;
    }

    public void close(String actor, Instant at, String reason) {
        status = InvestigationStatus.CLOSED;
        closedBy = actor;
        closedAt = at;
        closeReason = reason;
    }

    public void reopen() {
        status = InvestigationStatus.OPEN;
        closedBy = null;
        closedAt = null;
        closeReason = null;
    }

    public Long getId() {
        return id;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public String getTitle() {
        return title;
    }

    public InvestigationStatus getStatus() {
        return status;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getCloseReason() {
        return closeReason;
    }
}
