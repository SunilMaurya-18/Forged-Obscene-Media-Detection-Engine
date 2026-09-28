package com.forgeddetect.investigation;

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
 * Phase 26: a report that media with this hash was seen in a post on some platform. No image, URL,
 * username or raw account ID: the post is the partner's opaque ID, the account a keyed pseudonym.
 */
@Entity
@Table(name = "sightings", indexes = {
    @Index(name = "idx_sightings_account", columnList = "accountKey"),
    @Index(name = "idx_sightings_provenance", columnList = "provenanceId")
})
public class Sighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 64-bit pHash, lower-case hex; null if the sighting was reported by provenance ID only. */
    @Column(length = 16)
    private String phash;

    /** Provenance ID found in the post's embedded manifest (a labelled copy this platform issued). */
    @Column(length = 64)
    private String provenanceId;

    /** Normalised platform name, e.g. {@code pixelgram}. */
    @Column(nullable = false, length = 48)
    private String platform;

    /** The partner's own opaque post ID; only the partner can resolve it. */
    @Column(nullable = false, length = 128)
    private String postRef;

    /** HMAC-SHA256 of platform + account ID (hex); null if the reporter didn't name an account. */
    @Column(length = 64)
    private String accountKey;

    @Column(nullable = false)
    private Instant observedAt;

    @Column(nullable = false)
    private Instant reportedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SightingSource source;

    private Long apiClientId;

    /** Moderator who recorded it ({@link SightingSource#MODERATOR} only). */
    @Column(length = 64)
    private String reportedBy;

    /** platform|postRef|hash: the same post reported twice is one sighting. */
    @Column(nullable = false, unique = true, length = 256)
    private String dedupeKey;

    protected Sighting() {}

    public Sighting(
            String phash, String provenanceId, String platform, String postRef, String accountKey,
            Instant observedAt, Instant reportedAt, SightingSource source, Long apiClientId, String reportedBy) {
        this.phash = phash;
        this.provenanceId = provenanceId;
        this.platform = platform;
        this.postRef = postRef;
        this.accountKey = accountKey;
        this.observedAt = observedAt;
        this.reportedAt = reportedAt;
        this.source = source;
        this.apiClientId = apiClientId;
        this.reportedBy = reportedBy;
        this.dedupeKey = dedupeKey(platform, postRef, phash, provenanceId);
    }

    public static String dedupeKey(String platform, String postRef, String phash, String provenanceId) {
        return platform + "|" + postRef + "|" + (phash != null ? phash : "pid:" + provenanceId);
    }

    public Long getId() {
        return id;
    }

    public String getPhash() {
        return phash;
    }

    public String getProvenanceId() {
        return provenanceId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getPostRef() {
        return postRef;
    }

    public String getAccountKey() {
        return accountKey;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public SightingSource getSource() {
        return source;
    }

    public Long getApiClientId() {
        return apiClientId;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }
}
