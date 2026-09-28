package com.forgeddetect.state;

import java.time.Duration;
import java.util.Optional;

/**
 * Phase 24: short-lived counters, sets and locks that every backend instance must agree on: API
 * rate limits, wrong-PIN lockouts, moderator exposure counters and scheduler locks.
 *
 * <p>{@code app.state.store=memory} (default) keeps them in this JVM, which is right for one
 * instance and for tests (expiry follows the application {@link java.time.Clock}).
 * {@code app.state.store=redis} shares them through Redis, so a second instance sees the same
 * buckets and lockouts; expiry then follows Redis time.
 */
public interface SharedState {

    /** Token bucket after one call: whether a token was available, and how many are left. */
    record Bucket(boolean allowed, double tokens) {}

    /** Result of adding a member: whether it was new, and the set's size afterwards. */
    record SetAdd(boolean added, long size) {}

    /**
     * Atomic token bucket holding up to {@code capacity} tokens, refilled evenly over {@code window}.
     * Takes one token when {@code consume} and one is available.
     */
    Bucket takeToken(String key, int capacity, Duration window, boolean consume);

    /** Increments a counter; {@code ttl} is applied when the counter is created, not on later increments. */
    long increment(String key, Duration ttl);

    Optional<String> get(String key);

    /** Remaining lifetime of a key that exists and expires. */
    Optional<Duration> timeToLive(String key);

    /** Sets the key only if it doesn't exist (a lock, or "first writer wins"). */
    boolean setIfAbsent(String key, String value, Duration ttl);

    void delete(String key);

    /** Adds to a set and (re)sets the whole set's expiry to {@code ttl}. */
    SetAdd addToSet(String key, String member, Duration ttl);

    long setSize(String key);

    /** Changes the expiry of an existing key; no-op when it doesn't exist. */
    void expire(String key, Duration ttl);

    /** {@code memory} or {@code redis}. */
    String backend();

    /** True when state is shared with other instances (so cross-instance locks mean something). */
    boolean distributed();
}
