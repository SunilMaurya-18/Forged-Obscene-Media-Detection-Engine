package com.forgeddetect.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory token bucket per API client: {@code limit} requests in a burst, refilled evenly over a
 * minute. State is per backend instance and resets on restart; a multi-instance deployment would
 * move this to Redis.
 */
@Component
public class RateLimiter {

    static final double WINDOW_SECONDS = 60.0;

    private final Clock clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Takes one token if available. */
    public Result tryConsume(long clientId, int limit) {
        return bucket(clientId, limit).take(limit, nowSeconds(), true);
    }

    /** Current state without consuming a token (for the usage endpoint). */
    public Result peek(long clientId, int limit) {
        return bucket(clientId, limit).take(limit, nowSeconds(), false);
    }

    public void reset(long clientId) {
        buckets.remove(clientId);
    }

    private Bucket bucket(long clientId, int limit) {
        return buckets.computeIfAbsent(clientId, id -> new Bucket(limit, nowSeconds()));
    }

    private double nowSeconds() {
        return clock.millis() / 1000.0;
    }

    /**
     * @param retryAfterSeconds seconds until one token is available (0 when allowed)
     * @param resetSeconds seconds until the bucket is full again
     */
    public record Result(boolean allowed, int limit, int remaining, long retryAfterSeconds, long resetSeconds) {}

    private static final class Bucket {
        private double tokens;
        private double updatedAt;

        Bucket(int limit, double now) {
            this.tokens = limit;
            this.updatedAt = now;
        }

        synchronized Result take(int limit, double now, boolean consume) {
            double perSecond = limit / WINDOW_SECONDS;
            tokens = Math.min(limit, tokens + Math.max(0, now - updatedAt) * perSecond);
            updatedAt = now;
            boolean allowed = tokens >= 1;
            if (allowed && consume) {
                tokens -= 1;
            }
            long retryAfter = allowed ? 0 : (long) Math.ceil((1 - tokens) / perSecond);
            long reset = (long) Math.ceil((limit - tokens) / perSecond);
            return new Result(allowed, limit, (int) Math.floor(tokens), retryAfter, reset);
        }
    }
}
