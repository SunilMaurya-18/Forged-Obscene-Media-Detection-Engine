package com.forgeddetect.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Phase 28: a signed evidence package issued to one investigator under one legal request. The ZIP
 * holds manifests, reports, sightings, the custody log and public keys; never media.
 */
@Entity
@Table(name = "investigation_evidence_packages", indexes = @Index(name = "idx_evidence_packages_case", columnList = "investigationId"))
public class EvidencePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code PKG-XXXX-XXXX}. */
    @Column(nullable = false, unique = true, length = 16)
    private String packageId;

    @Column(nullable = false)
    private Long investigationId;

    @Column(nullable = false, length = 16)
    private String requestNumber;

    @Column(nullable = false, length = 64)
    private String investigator;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant downloadExpiresAt;

    @Column(nullable = false, length = 16)
    private String preservationId;

    @Column(nullable = false)
    private int custodyLength;

    @Column(nullable = false, length = 64)
    private String custodyHead;

    /** SHA-256 of {@code PACKAGE-MANIFEST.txt}, the text that is signed. */
    @Column(nullable = false, length = 64)
    private String manifestSha256;

    @Column(nullable = false, length = 16)
    private String signingKeyId;

    private Instant timestampAt;

    /** SHA-256 of the ZIP exactly as it is downloaded. */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private int size;

    @Column(nullable = false)
    private int downloads;

    private Instant lastDownloadedAt;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    protected EvidencePackage() {}

    public EvidencePackage(String packageId, Long investigationId, String requestNumber, String investigator, Instant issuedAt,
            Instant downloadExpiresAt, String preservationId, int custodyLength, String custodyHead, String manifestSha256,
            String signingKeyId, Instant timestampAt, String sha256, byte[] content) {
        this.packageId = packageId;
        this.investigationId = investigationId;
        this.requestNumber = requestNumber;
        this.investigator = investigator;
        this.issuedAt = issuedAt;
        this.downloadExpiresAt = downloadExpiresAt;
        this.preservationId = preservationId;
        this.custodyLength = custodyLength;
        this.custodyHead = custodyHead;
        this.manifestSha256 = manifestSha256;
        this.signingKeyId = signingKeyId;
        this.timestampAt = timestampAt;
        this.sha256 = sha256;
        this.size = content.length;
        this.content = content;
    }

    void downloaded(Instant at) {
        downloads++;
        lastDownloadedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getPackageId() {
        return packageId;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public String getRequestNumber() {
        return requestNumber;
    }

    public String getInvestigator() {
        return investigator;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getDownloadExpiresAt() {
        return downloadExpiresAt;
    }

    public String getPreservationId() {
        return preservationId;
    }

    public int getCustodyLength() {
        return custodyLength;
    }

    public String getCustodyHead() {
        return custodyHead;
    }

    public String getManifestSha256() {
        return manifestSha256;
    }

    public String getSigningKeyId() {
        return signingKeyId;
    }

    public Instant getTimestampAt() {
        return timestampAt;
    }

    public String getSha256() {
        return sha256;
    }

    public int getSize() {
        return size;
    }

    public int getDownloads() {
        return downloads;
    }

    public Instant getLastDownloadedAt() {
        return lastDownloadedAt;
    }

    public byte[] getContent() {
        return content;
    }
}
