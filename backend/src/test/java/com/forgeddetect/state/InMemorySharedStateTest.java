package com.forgeddetect.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeddetect.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySharedStateTest extends SharedStateContract {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T00:00:00Z"));
    private final InMemorySharedState state = new InMemorySharedState(clock);

    @Override
    SharedState instanceA() {
        return state;
    }

    @Override
    SharedState instanceB() {
        return state;
    }

    @Test
    void entriesExpireOnTheApplicationClock() {
        state.increment("n", Duration.ofMinutes(1));
        state.setIfAbsent("lock", "x", Duration.ofMinutes(2));
        state.addToSet("s", "a", Duration.ofMinutes(3));

        clock.advance(Duration.ofSeconds(61));
        assertThat(state.get("n")).isEmpty();
        assertThat(state.increment("n", Duration.ofMinutes(1))).as("restarts after expiry").isEqualTo(1);
        assertThat(state.get("lock")).contains("x");

        state.expire("s", Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(61));
        assertThat(state.get("lock")).isEmpty();
        assertThat(state.setSize("s")).isZero();
        assertThat(state.distributed()).isFalse();
    }
}
