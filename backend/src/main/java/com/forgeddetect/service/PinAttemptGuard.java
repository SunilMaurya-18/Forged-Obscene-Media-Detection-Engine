package com.forgeddetect.service;

import com.forgeddetect.state.SharedState;
import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Per-reference wrong-PIN counter: after {@code maxAttempts} failures within {@code lockout} the
 * reference is locked for {@code lockout}. Phase 24: counters and locks live in {@link SharedState},
 * so a lockout holds on every backend instance, not just the one that saw the failures.
 */
public final class PinAttemptGuard {

    public static class TooManyAttempts extends RuntimeException {
        private final long retryAfterSeconds;

        public TooManyAttempts(long retryAfterSeconds) {
            super("Too many attempts");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final SharedState state;
    private final String scope;
    private final IntSupplier maxAttempts;
    private final Supplier<Duration> lockout;

    /** @param scope keeps case numbers and notice numbers in separate key spaces */
    public PinAttemptGuard(SharedState state, String scope, IntSupplier maxAttempts, Supplier<Duration> lockout) {
        this.state = state;
        this.scope = scope;
        this.maxAttempts = maxAttempts;
        this.lockout = lockout;
    }

    /** @throws TooManyAttempts while {@code key} is locked */
    public void checkNotLocked(String key) {
        state.timeToLive(lockKey(key)).ifPresent(ttl -> {
            throw new TooManyAttempts(Math.max(1, (ttl.toMillis() + 999) / 1000));
        });
    }

    public void success(String key) {
        state.delete(failKey(key));
    }

    public void failure(String key) {
        long failures = state.increment(failKey(key), lockout.get());
        if (failures >= maxAttempts.getAsInt()) {
            state.setIfAbsent(lockKey(key), "1", lockout.get());
            state.delete(failKey(key));
        }
    }

    private String failKey(String key) {
        return "pin:" + scope + ":fail:" + key;
    }

    private String lockKey(String key) {
        return "pin:" + scope + ":lock:" + key;
    }
}
