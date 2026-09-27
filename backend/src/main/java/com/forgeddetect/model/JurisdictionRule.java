package com.forgeddetect.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Phase 19: one takedown deadline per (jurisdiction, complaint type). Rows are data, not code, so a
 * new jurisdiction or a changed statute is an INSERT / UPDATE, not a deploy.
 */
@Entity
@Table(name = "jurisdiction_rules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"jurisdiction", "complaint_type"}))
public class JurisdictionRule {

    public static final String GENERIC = "GENERIC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO 3166-1 alpha-2 code (IN, US, ...) or GENERIC. */
    @Column(nullable = false, length = 16)
    private String jurisdiction;

    @Enumerated(EnumType.STRING)
    @Column(name = "complaint_type", nullable = false, length = 32)
    private ComplaintType complaintType;

    @Column(nullable = false)
    private int deadlineHours;

    /** Short badge text, e.g. "India IT Rules 2026". */
    @Column(nullable = false, length = 64)
    private String label;

    /** Fuller citation shown in reports and the rules table. */
    @Column(length = 240)
    private String legalReference;

    protected JurisdictionRule() {}

    public JurisdictionRule(String jurisdiction, ComplaintType complaintType, int deadlineHours, String label, String legalReference) {
        this.jurisdiction = jurisdiction;
        this.complaintType = complaintType;
        this.deadlineHours = deadlineHours;
        this.label = label;
        this.legalReference = legalReference;
    }

    public Long getId() {
        return id;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public ComplaintType getComplaintType() {
        return complaintType;
    }

    public int getDeadlineHours() {
        return deadlineHours;
    }

    public String getLabel() {
        return label;
    }

    public String getLegalReference() {
        return legalReference;
    }
}
