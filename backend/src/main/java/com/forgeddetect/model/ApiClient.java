package com.forgeddetect.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A third-party platform allowed to call {@code /api/v1}. Only the SHA-256 of its key is stored. */
@Entity
@Table(name = "api_clients")
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    /** Hex SHA-256 of the full key; the key itself is shown once at creation and never persisted. */
    @Column(nullable = false, unique = true, length = 64)
    private String keyHash;

    /** Leading characters of the key, safe to display so clients can tell keys apart. */
    @Column(nullable = false, length = 24)
    private String keyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanTier plan;

    @Column(nullable = false)
    private long requestCount;

    @Column(nullable = false)
    private long throttledCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private Instant lastUsedAt;

    private Instant revokedAt;

    protected ApiClient() {}

    public ApiClient(String name, PlanTier plan, String keyHash, String keyPrefix) {
        this.name = name;
        this.plan = plan;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public PlanTier getPlan() {
        return plan;
    }

    public void setPlan(PlanTier plan) {
        this.plan = plan;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getThrottledCount() {
        return throttledCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
