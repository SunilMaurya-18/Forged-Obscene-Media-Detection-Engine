package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.JurisdictionRule;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.JurisdictionRuleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the takedown clock for complaint items: starts it, recalculates status on a schedule,
 * escalates on AT_RISK / BREACHED transitions, and stops it when a moderator resolves the item.
 *
 * <p>Phase 19: the deadline comes from the {@code jurisdiction_rules} table, looked up as
 * (jurisdiction, type), then (GENERIC, type), then the configured {@code compliance.sla.*} window.
 */
@Service
public class SlaClockService {

    private static final Logger log = LoggerFactory.getLogger(SlaClockService.class);
    private static final Pattern JURISDICTION = Pattern.compile("^[A-Z]{2,16}$");

    /** The rule that governs one complaint. {@code ruleId} is null for the configured last-resort window. */
    public record ResolvedRule(
            String jurisdiction, ComplaintType type, Duration window, String label, String legalReference, Long ruleId, boolean fallback) {

        public String badge() {
            return SlaClockService.badge(label, window);
        }
    }

    private final AnalysisResultRepository repository;
    private final ComplianceProperties properties;
    private final EscalationService escalationService;
    private final JurisdictionRuleRepository rules;
    private final Clock clock;

    public SlaClockService(
            AnalysisResultRepository repository,
            ComplianceProperties properties,
            EscalationService escalationService,
            JurisdictionRuleRepository rules,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.escalationService = escalationService;
        this.rules = rules;
        this.clock = clock;
    }

    /** Upper-cases and validates a jurisdiction code; null/blank means the platform default. */
    public String normalizeJurisdiction(String code) {
        String j = code == null || code.isBlank() ? properties.getDefaultJurisdiction() : code;
        j = j.trim().toUpperCase(Locale.ROOT);
        if (!JURISDICTION.matcher(j).matches()) {
            throw new IllegalArgumentException("jurisdiction must be a country code such as IN or US, or GENERIC");
        }
        return j;
    }

    public ResolvedRule rule(String jurisdiction, ComplaintType type) {
        String j = normalizeJurisdiction(jurisdiction);
        Optional<JurisdictionRule> hit = rules.findByJurisdictionAndComplaintType(j, type);
        boolean fallback = false;
        if (hit.isEmpty() && !JurisdictionRule.GENERIC.equals(j)) {
            hit = rules.findByJurisdictionAndComplaintType(JurisdictionRule.GENERIC, type);
            fallback = true;
        }
        if (hit.isPresent()) {
            JurisdictionRule r = hit.get();
            return new ResolvedRule(j, type, Duration.ofHours(r.getDeadlineHours()), r.getLabel(), r.getLegalReference(), r.getId(), fallback);
        }
        return new ResolvedRule(j, type, properties.windowFor(type), "Configured default",
                "compliance.sla." + (type == ComplaintType.GOVERNMENT_ORDER ? "government-order" : "user-complaint"), null, true);
    }

    /** Deadline under the platform's default jurisdiction. */
    public Instant deadlineFor(ComplaintType type, Instant receivedAt) {
        return receivedAt.plus(rule(null, type).window());
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

    /**
     * Attaches a complaint to an item and starts its clock from {@code receivedAt} under the item's
     * jurisdiction (the platform default if unset). Does not save.
     */
    public ResolvedRule startClock(AnalysisResult item, ComplaintType type, String reference, Instant receivedAt) {
        ResolvedRule rule = rule(item.getJurisdiction(), type);
        item.setJurisdiction(rule.jurisdiction());
        item.setSlaRuleLabel(rule.label());
        item.setComplaintType(type);
        item.setComplaintReference(reference);
        item.setComplaintReceivedAt(receivedAt);
        item.setSlaDeadline(receivedAt.plus(rule.window()));
        item.setSlaStatus(statusAt(item.getSlaDeadline(), clock.instant()));
        return rule;
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

    /** The window this item was given (deadline minus receipt), or null if it isn't a complaint. */
    public static Duration window(AnalysisResult item) {
        return item.getComplaintReceivedAt() != null && item.getSlaDeadline() != null
                ? Duration.between(item.getComplaintReceivedAt(), item.getSlaDeadline())
                : null;
    }

    /** "India IT Rules 2026 — 2h", "US Take It Down Act — 48h". */
    public static String badge(String label, Duration window) {
        if (label == null || window == null) {
            return null;
        }
        long minutes = window.toMinutes();
        String span = minutes % 60 == 0 ? (minutes / 60) + "h" : minutes + "m";
        return label + " \u2014 " + span;
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
