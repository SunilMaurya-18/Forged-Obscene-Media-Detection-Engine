package com.forgeddetect.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Phase 21: a self-service report filed through the public wizard. The analysis, SLA clock and
 * decision live on the linked {@link AnalysisResult}; this row only holds what the reporter needs to
 * follow up without an account. The PIN is stored as a BCrypt hash and shown to the reporter once.
 */
@Entity
@Table(name = "complaint_cases")
public class Complaint {

    public enum Relationship { SELF, ON_BEHALF }

    public enum Channel { EMAIL, SMS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-friendly, e.g. {@code FMD-7K3Q-9XPA}; never the database id. */
    @Column(nullable = false, unique = true, length = 16)
    private String caseNumber;

    @Column(nullable = false, length = 80)
    private String pinHash;

    @Column(nullable = false, unique = true)
    private Long analysisId;

    @Column(nullable = false, length = 16)
    private String relationship;

    /** Optional free text from the reporter (where it was seen, anything else), capped at 1000 characters. */
    @Column(length = 1000)
    private String details;

    /** Optional email or phone for status updates; only used by the notification hook. */
    @Column(length = 254)
    private String contact;

    @Column(length = 8)
    private String contactChannel;

    @Column(nullable = false, length = 8)
    private String locale = "en";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Column(length = 24)
    private String lastNotifiedStage;

    private Instant lastNotifiedAt;

    public Long getId() {
        return id;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public void setCaseNumber(String caseNumber) {
        this.caseNumber = caseNumber;
    }

    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(Long analysisId) {
        this.analysisId = analysisId;
    }

    public String getRelationship() {
        return relationship;
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getContactChannel() {
        return contactChannel;
    }

    public void setContactChannel(String contactChannel) {
        this.contactChannel = contactChannel;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastNotifiedStage() {
        return lastNotifiedStage;
    }

    public void setLastNotifiedStage(String lastNotifiedStage) {
        this.lastNotifiedStage = lastNotifiedStage;
    }

    public Instant getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(Instant lastNotifiedAt) {
        this.lastNotifiedAt = lastNotifiedAt;
    }
}
