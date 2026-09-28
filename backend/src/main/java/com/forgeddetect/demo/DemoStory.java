package com.forgeddetect.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Phase 25: the IDs behind one seeded demo story, so the tour can link to live records. Everything it
 * points at is synthetic. The notice and case PINs are kept in plain text here, unlike real ones,
 * because the tour hands them to every visitor.
 */
@Entity
@Table(name = "demo_stories")
public class DemoStory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant createdAt;

    /** Last full reset of the demo data (null if it was never reset). */
    private Instant resetAt;

    private Long flaggedId;

    @Column(length = 24)
    private String noticeNumber;

    @Column(length = 12)
    private String noticePin;

    private Long appealId;

    @Column(length = 64)
    private String reportId;

    @Column(length = 64)
    private String reportSha256;

    /** Rendered without an original, so it holds scores and forensic grids only, never media. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(columnDefinition = "bytea")
    private byte[] reportPdf;

    private Long atRiskId;

    @Column(length = 16)
    private String caseNumber;

    @Column(length = 12)
    private String casePin;

    private Long breachedId;

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getResetAt() {
        return resetAt;
    }

    public void setResetAt(Instant resetAt) {
        this.resetAt = resetAt;
    }

    public Long getFlaggedId() {
        return flaggedId;
    }

    public void setFlaggedId(Long flaggedId) {
        this.flaggedId = flaggedId;
    }

    public String getNoticeNumber() {
        return noticeNumber;
    }

    public void setNoticeNumber(String noticeNumber) {
        this.noticeNumber = noticeNumber;
    }

    public String getNoticePin() {
        return noticePin;
    }

    public void setNoticePin(String noticePin) {
        this.noticePin = noticePin;
    }

    public Long getAppealId() {
        return appealId;
    }

    public void setAppealId(Long appealId) {
        this.appealId = appealId;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getReportSha256() {
        return reportSha256;
    }

    public void setReportSha256(String reportSha256) {
        this.reportSha256 = reportSha256;
    }

    public byte[] getReportPdf() {
        return reportPdf;
    }

    public void setReportPdf(byte[] reportPdf) {
        this.reportPdf = reportPdf;
    }

    public Long getAtRiskId() {
        return atRiskId;
    }

    public void setAtRiskId(Long atRiskId) {
        this.atRiskId = atRiskId;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public void setCaseNumber(String caseNumber) {
        this.caseNumber = caseNumber;
    }

    public String getCasePin() {
        return casePin;
    }

    public void setCasePin(String casePin) {
        this.casePin = casePin;
    }

    public Long getBreachedId() {
        return breachedId;
    }

    public void setBreachedId(Long breachedId) {
        this.breachedId = breachedId;
    }
}
