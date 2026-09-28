package com.forgeddetect.security;

import com.forgeddetect.state.SharedState;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Token bucket per API client: {@code limit} requests in a burst, refilled evenly over a minute.
 * Phase 24: buckets live in {@link SharedState}, so with {@code STATE_STORE=redis} every backend
 * instance draws from the same bucket.
 */
@Component
public class RateLimiter {

    static final double WINDOW_SECONDS = 60.0;
    private static final Duration WINDOW = Duration.ofSeconds((long) WINDOW_SECONDS);

    private final SharedState state;

    public RateLimiter(SharedState state) {
        this.state = state;
    }

    /** Takes one token if available. */
    public Result tryConsume(long clientId, int limit) {
        return result(state.takeToken(key(clientId), limit, WINDOW, true), limit);
    }

    /** Current state without consuming a token (for the usage endpoint). */
    public Result peek(long clientId, int limit) {
        return result(state.takeToken(key(clientId), limit, WINDOW, false), limit);
    }

    public void reset(long clientId) {
        state.delete(key(clientId));
    }

    private static String key(long clientId) {
        return "ratelimit:api:" + clientId;
    }

    private static Result result(SharedState.Bucket bucket, int limit) {
        double perSecond = limit / WINDOW_SECONDS;
        double tokens = bucket.tokens();
        long retryAfter = bucket.allowed() ? 0 : (long) Math.ceil((1 - tokens) / perSecond);
        long reset = (long) Math.ceil((limit - tokens) / perSecond);
        return new Result(bucket.allowed(), limit, (int) Math.floor(tokens), retryAfter, reset);
    }

    /**
     * @param retryAfterSeconds seconds until one token is available (0 when allowed)
     * @param resetSeconds seconds until the bucket is full again
     */
    public record Result(boolean allowed, int limit, int remaining, long retryAfterSeconds, long resetSeconds) {}
}
