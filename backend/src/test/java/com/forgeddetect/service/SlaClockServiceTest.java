package com.forgeddetect.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.JurisdictionRuleRepository;
import com.forgeddetect.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure SLA arithmetic: 2h user complaints, 3h government orders, AT_RISK at <= 30 min remaining. */
class SlaClockServiceTest {

    private static final Instant T0 = Instant.parse("2026-02-20T10:00:00Z");

    private MutableClock clock;
    private SlaClockService sla;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        sla = new SlaClockService(
                mock(AnalysisResultRepository.class),
                new ComplianceProperties(),
                mock(EscalationService.class),
                mock(JurisdictionRuleRepository.class),
                clock);
    }

    @Test
    void deadlinesFollowStatutoryWindows() {
        assertEquals(T0.plus(Duration.ofHours(2)), sla.deadlineFor(ComplaintType.USER_COMPLAINT, T0));
        assertEquals(T0.plus(Duration.ofHours(3)), sla.deadlineFor(ComplaintType.GOVERNMENT_ORDER, T0));
    }

    @ParameterizedTest(name = "{0} min remaining -> {1}")
    @CsvSource({
        "120, ON_TRACK",
        "31,  ON_TRACK",
        "30,  AT_RISK",
        "1,   AT_RISK",
        "0,   BREACHED",
        "-5,  BREACHED"
    })
    void statusBoundaries(long minutesRemaining, SlaStatus expected) {
        Instant deadline = T0.plus(Duration.ofMinutes(minutesRemaining));
        assertEquals(expected, sla.statusAt(deadline, T0));
    }

    @Test
    void stopClockFreezesStatusAndRemainingTime() {
        AnalysisResult item = new AnalysisResult();
        sla.startClock(item, ComplaintType.USER_COMPLAINT, "REF-1", T0);
        assertEquals(SlaStatus.ON_TRACK, item.getSlaStatus());

        clock.advance(Duration.ofMinutes(100));
        sla.stopClock(item);
        assertEquals(SlaStatus.AT_RISK, item.getSlaStatus());
        assertEquals(Duration.ofMinutes(20), sla.remaining(item));

        clock.advance(Duration.ofHours(5));
        assertEquals(Duration.ofMinutes(20), sla.remaining(item), "remaining time is frozen once resolved");
    }
}
