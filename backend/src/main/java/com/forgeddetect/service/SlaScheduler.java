package com.forgeddetect.service;

import com.forgeddetect.state.SharedState;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SlaClockService#recalculate()} (complaints) and {@link AppealService#recalculate()}
 * (appeals) every {@code compliance.sla.check-interval}. With shared state (several backend instances)
 * only the instance that takes the per-interval lock runs them, so an escalation webhook fires once,
 * not once per instance.
 */
@Component
public class SlaScheduler {

    static final String LOCK = "lock:sla-recalculate";

    private final SlaClockService slaClock;
    private final AppealService appeals;
    private final SharedState state;
    private final Duration hold;
    private final String instance = UUID.randomUUID().toString();

    public SlaScheduler(
            SlaClockService slaClock,
            AppealService appeals,
            SharedState state,
            @Value("${compliance.sla.check-interval:PT15S}") Duration interval) {
        this.slaClock = slaClock;
        this.appeals = appeals;
        this.state = state;
        Duration h = interval.multipliedBy(4).dividedBy(5);
        this.hold = h.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : h;
    }

    @Scheduled(
            fixedDelayString = "${compliance.sla.check-interval:PT15S}",
            initialDelayString = "${compliance.sla.check-interval:PT15S}")
    public void run() {
        if (state.distributed() && !state.setIfAbsent(LOCK, instance, hold)) {
            return;
        }
        slaClock.recalculate();
        appeals.recalculate();
    }
}
