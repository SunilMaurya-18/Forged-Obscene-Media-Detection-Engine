package com.forgeddetect.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** An analysis that is part of a case: the media whose appearances the case tracks. */
@Entity
@Table(name = "investigation_items", uniqueConstraints = @UniqueConstraint(columnNames = {"investigationId", "analysisId"}))
public class InvestigationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long investigationId;

    @Column(nullable = false)
    private Long analysisId;

    @Column(nullable = false, length = 64)
    private String addedBy;

    @Column(nullable = false)
    private Instant addedAt;

    protected InvestigationItem() {}

    public InvestigationItem(Long investigationId, Long analysisId, String addedBy, Instant addedAt) {
        this.investigationId = investigationId;
        this.analysisId = analysisId;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
