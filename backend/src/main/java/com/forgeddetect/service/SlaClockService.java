package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the takedown clock for complaint items: starts it, recalculates status on a schedule,
 * escalates on AT_RISK / BREACHED transitions, and stops it when a moderator resolves the item.
 */
@Service
public class SlaClockService {

    private static final Logger log = LoggerFactory.getLogger(SlaClockService.class);

    private final AnalysisResultRepository repository;
    private final ComplianceProperties properties;
    private final EscalationService escalationService;
    private final Clock clock;

    public SlaClockService(
            AnalysisResultRepository repository,
            ComplianceProperties properties,
            EscalationService escalationService,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.escalationService = escalationService;
        this.clock = clock;
    }

    public Instant deadlineFor(ComplaintType type, Instant receivedAt) {
        return receivedAt.plus(properties.windowFor(type));
    }

    /** Deadline reached (zero remaining) counts as breached. */
    public SlaStatus statusAt(Instant deadline, Instant now) {
        Duration remaining = Duration.between(now, deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return SlaStatus.BREACHED;
        }
        if (remaining.compareTo(properties.getSla().getAtRiskThreshold()) <= 0) {
            return SlaStatus.AT_RISK;
        }
        return SlaStatus.ON_TRACK;
    }

    /** Attaches a complaint to an item and starts its clock from {@code receivedAt}. Does not save. */
    public void startClock(AnalysisResult item, ComplaintType type, String reference, Instant receivedAt) {
        item.setComplaintType(type);
        item.setComplaintReference(reference);
        item.setComplaintReceivedAt(receivedAt);
        item.setSlaDeadline(deadlineFor(type, receivedAt));
        item.setSlaStatus(statusAt(item.getSlaDeadline(), clock.instant()));
    }

    /** Stops the clock; the status is frozen at whatever it was at resolution time. Does not save. */
    public void stopClock(AnalysisResult item) {
        if (!item.isComplianceOpen()) {
            return;
        }
        Instant now = clock.instant();
        item.setSlaStatus(statusAt(item.getSlaDeadline(), now));
        item.setResolvedAt(now);
    }

    public Duration remaining(AnalysisResult item) {
        Instant end = item.getResolvedAt() != null ? item.getResolvedAt() : clock.instant();
        return Duration.between(end, item.getSlaDeadline());
    }

    /** @return number of items whose status changed */
    @Scheduled(
            fixedDelayString = "${compliance.sla.check-interval:PT15S}",
            initialDelayString = "${compliance.sla.check-interval:PT15S}")
    @Transactional
    public int recalculate() {
        Instant now = clock.instant();
        List<AnalysisResult> open = repository.findByComplaintTypeIsNotNullAndResolvedAtIsNullOrderBySlaDeadlineAsc();
        int changed = 0;
        for (AnalysisResult item : open) {
            SlaStatus next = statusAt(item.getSlaDeadline(), now);
            if (next == item.getSlaStatus()) {
                continue;
            }
            SlaStatus previous = item.getSlaStatus();
            item.setSlaStatus(next);
            if (next != SlaStatus.ON_TRACK) {
                escalationService.escalate(item, next);
            }
            repository.save(item);
            changed++;
            log.info("SLA analysis #{}: {} -> {}", item.getId(), previous, next);
        }
        return changed;
    }
}
