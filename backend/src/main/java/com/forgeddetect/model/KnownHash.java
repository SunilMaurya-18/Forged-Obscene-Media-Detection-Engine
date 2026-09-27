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
import java.time.Instant;

/**
 * Phase 18: a perceptual hash of content already confirmed as abusive. Only the hash is kept;
 * there is no image, thumbnail or URL behind a row.
 */
@Entity
@Table(name = "known_hashes", uniqueConstraints = @UniqueConstraint(columnNames = {"algorithm", "hash"}))
public class KnownHash {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower-case hex. 16 chars for the 64-bit pHash, 64 chars for a 256-bit PDQ hash. */
    @Column(nullable = false, length = 64)
    private String hash;

    /** {@code phash} or {@code pdq}; hashes are only ever compared within the same algorithm. */
    @Column(nullable = false, length = 16)
    private String algorithm = "phash";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnownHashSource source;

    @Column(nullable = false, updatable = false)
    private Instant addedAt;

    @Column(length = 160)
    private String note;

    protected KnownHash() {}

    public KnownHash(String hash, String algorithm, KnownHashSource source, Instant addedAt, String note) {
        this.hash = hash;
        this.algorithm = algorithm;
        this.source = source;
        this.addedAt = addedAt;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getHash() {
        return hash;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public KnownHashSource getSource() {
        return source;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public String getNote() {
        return note;
    }
}
