package com.forgeddetect.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Phase 27: a signed snapshot of what a case contained at one moment: hashes and scores of its
 * analyses, their signed reports, the linked sightings and the custody head. Commitments only; the
 * media itself stays with whoever hosts it (ADR 0006).
 */
@Entity
@Table(name = "investigation_preservations", indexes = @Index(name = "idx_preservations_case", columnList = "investigationId"))
public class Preservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code PRS-XXXX-XXXX}. */
    @Column(nullable = false, unique = true, length = 16)
    private String preservationId;

    @Column(nullable = false)
    private Long investigationId;

    @Column(nullable = false)
    private Instant preservedAt;

    @Column(nullable = false, length = 64)
    private String preservedBy;

    @Column(length = 512)
    private String reason;

    /** The custody chain the manifest commits to: its length and newest hash at preservation time. */
    @Column(nullable = false)
    private int custodyLength;

    @Column(nullable = false, length = 64)
    private String custodyHead;

    private int analysisCount;

    private int sightingCount;

    @Column(nullable = false, length = PreservationService.MAX_MANIFEST)
    private String manifest;

    /** SHA-256 (hex) of {@link #manifest}. */
    @Column(nullable = false, length = 64)
    private String manifestHash;

    /** Ed25519 over the manifest hash, base64. */
    @Column(nullable = false, length = 128)
    private String signature;

    @Column(nullable = false, length = 16)
    private String signingKeyId;

    /** RFC 3161 token over the signature, base64; null when no TSA is configured or it was unreachable. */
    @Column(length = 16000)
    private String timestampToken;

    private Instant timestampAt;

    @Column(length = 255)
    private String timestampAuthority;

    protected Preservation() {}

    public Preservation(
            String preservationId, Long investigationId, Instant preservedAt, String preservedBy, String reason,
            int custodyLength, String custodyHead, int analysisCount, int sightingCount) {
        this.preservationId = preservationId;
        this.investigationId = investigationId;
        this.preservedAt = preservedAt;
        this.preservedBy = preservedBy;
        this.reason = reason;
        this.custodyLength = custodyLength;
        this.custodyHead = custodyHead;
        this.analysisCount = analysisCount;
        this.sightingCount = sightingCount;
    }

    void sign(String manifest, String manifestHash, String signature, String signingKeyId) {
        this.manifest = manifest;
        this.manifestHash = manifestHash;
        this.signature = signature;
        this.signingKeyId = signingKeyId;
    }

    void timestamp(String token, Instant at, String authority) {
        this.timestampToken = token;
        this.timestampAt = at;
        this.timestampAuthority = authority;
    }

    public Long getId() {
        return id;
    }

    public String getPreservationId() {
        return preservationId;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public Instant getPreservedAt() {
        return preservedAt;
    }

    public String getPreservedBy() {
        return preservedBy;
    }

    public String getReason() {
        return reason;
    }

    public int getCustodyLength() {
        return custodyLength;
    }

    public String getCustodyHead() {
        return custodyHead;
    }

    public int getAnalysisCount() {
        return analysisCount;
    }

    public int getSightingCount() {
        return sightingCount;
    }

    public String getManifest() {
        return manifest;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public String getSignature() {
        return signature;
    }

    public String getSigningKeyId() {
        return signingKeyId;
    }

    public String getTimestampToken() {
        return timestampToken;
    }

    public Instant getTimestampAt() {
        return timestampAt;
    }

    public String getTimestampAuthority() {
        return timestampAuthority;
    }
}
