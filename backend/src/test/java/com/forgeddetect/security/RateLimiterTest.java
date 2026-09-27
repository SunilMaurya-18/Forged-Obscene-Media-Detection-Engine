package com.forgeddetect.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** Minimal settable clock so refill is deterministic. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void burstThenThrottleThenRefillEvenlyOverAMinute() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock);

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryConsume(1L, 6).allowed()).isTrue();
        }
        RateLimiter.Result blocked = limiter.tryConsume(1L, 6);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(10);
        assertThat(blocked.resetSeconds()).isEqualTo(60);

        assertThat(limiter.tryConsume(2L, 6).allowed()).as("buckets are per client").isTrue();

        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.peek(1L, 6).remaining()).isEqualTo(1);
        assertThat(limiter.tryConsume(1L, 6).allowed()).isTrue();
        assertThat(limiter.tryConsume(1L, 6).allowed()).isFalse();

        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.peek(1L, 6).remaining()).as("never exceeds the burst size").isEqualTo(6);
    }
}
