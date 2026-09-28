package com.forgeddetect.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeddetect.security.RateLimiter;
import com.forgeddetect.service.PinAttemptGuard;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link SharedState} backend must have. {@link #instanceA()} and {@link #instanceB()}
 * stand for two backend instances: for Redis they are separate clients of one server.
 */
abstract class SharedStateContract {

    abstract SharedState instanceA();

    abstract SharedState instanceB();

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    void countersLocksAndSets() {
        SharedState a = instanceA();
        SharedState b = instanceB();
        String counter = key();
        assertThat(a.increment(counter, Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(b.increment(counter, Duration.ofMinutes(5))).isEqualTo(2);
        assertThat(a.timeToLive(counter)).hasValueSatisfying(ttl -> assertThat(ttl).isPositive());

        String lock = key();
        assertThat(a.setIfAbsent(lock, "a", Duration.ofMinutes(1))).isTrue();
        assertThat(b.setIfAbsent(lock, "b", Duration.ofMinutes(1))).isFalse();
        assertThat(b.get(lock)).contains("a");
        a.delete(lock);
        assertThat(b.get(lock)).isEmpty();

        String set = key();
        assertThat(a.addToSet(set, "1", Duration.ofMinutes(1))).isEqualTo(new SharedState.SetAdd(true, 1));
        assertThat(b.addToSet(set, "1", Duration.ofMinutes(1))).isEqualTo(new SharedState.SetAdd(false, 1));
        assertThat(b.addToSet(set, "2", Duration.ofMinutes(1))).isEqualTo(new SharedState.SetAdd(true, 2));
        assertThat(a.setSize(set)).isEqualTo(2);
        assertThat(a.setSize(key())).isZero();
    }

    @Test
    void twoInstancesShareOneRateLimitBucket() {
        RateLimiter a = new RateLimiter(instanceA());
        RateLimiter b = new RateLimiter(instanceB());
        long client = Math.abs(UUID.randomUUID().getMostSignificantBits());
        for (int i = 0; i < 3; i++) {
            assertThat(a.tryConsume(client, 6).allowed()).isTrue();
            assertThat(b.tryConsume(client, 6).allowed()).isTrue();
        }
        assertThat(a.tryConsume(client, 6).allowed()).as("6 requests spread over two instances exhaust one bucket").isFalse();
        assertThat(b.tryConsume(client, 6).allowed()).isFalse();
        assertThat(b.peek(client, 6).retryAfterSeconds()).isPositive();
    }

    @Test
    void aLockoutOnOneInstanceHoldsOnTheOther() {
        PinAttemptGuard a = new PinAttemptGuard(instanceA(), "case", () -> 3, () -> Duration.ofMinutes(15));
        PinAttemptGuard b = new PinAttemptGuard(instanceB(), "case", () -> 3, () -> Duration.ofMinutes(15));
        String caseNumber = "FMD-" + UUID.randomUUID();
        a.failure(caseNumber);
        b.failure(caseNumber);
        b.checkNotLocked(caseNumber);
        a.failure(caseNumber);
        Assertions.assertThatThrownBy(() -> b.checkNotLocked(caseNumber))
                .isInstanceOf(PinAttemptGuard.TooManyAttempts.class)
                .satisfies(ex -> assertThat(((PinAttemptGuard.TooManyAttempts) ex).retryAfterSeconds()).isBetween(1L, 900L));
    }
}
