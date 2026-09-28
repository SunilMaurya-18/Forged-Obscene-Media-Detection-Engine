package com.forgeddetect.observability;

import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.DecisionChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 24: domain metrics on {@code /actuator/prometheus} (HTTP latency comes from Spring's own
 * {@code http_server_requests_seconds}). Aggregates only: no IDs, hashes or usernames in any tag.
 *
 * <ul>
 *   <li>{@code fmd_decisions_total{source=model|moderator, decision, media}}</li>
 *   <li>{@code fmd_moderation_overturns_total}: a moderator reversed an AUTO_FLAG</li>
 *   <li>{@code fmd_sla_escalations_total{status, jurisdiction, type}} and {@code fmd_sla_breaches_total}</li>
 *   <li>{@code fmd_sla_open{status}} and {@code fmd_review_queue}: current backlog (queried per scrape)</li>
 *   <li>{@code fmd_model_score}: served score distribution per model (drift signal)</li>
 *   <li>{@code fmd_ml_client_errors_total{endpoint, reason}}: failed calls to the ML service</li>
 *   <li>{@code fmd_api_rate_limited_total{plan}}: partner API requests refused with 429</li>
 *   <li>{@code fmd_sightings_total{source, linked}}: reported sightings, and whether they matched a case</li>
 * </ul>
 */
@Component
public class FmdMetrics {

    private static final double[] SCORE_BUCKETS = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95, 1.0};

    private final MeterRegistry registry;

    public FmdMetrics(MeterRegistry registry, AnalysisResultRepository results) {
        this.registry = registry;
        for (SlaStatus status : SlaStatus.values()) {
            Gauge.builder("fmd.sla.open", results, r -> safe(() -> r.countByComplaintTypeIsNotNullAndResolvedAtIsNullAndSlaStatus(status)))
                    .description("Open complaints by SLA status")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("fmd.review.queue", results, r -> safe(() -> r.countByDecision(Decision.REVIEW)))
                .description("Items waiting for a moderator")
                .register(registry);
    }

    public void modelDecision(Decision decision, boolean video, double forgery, double explicit) {
        decisions("model", decision, video ? "video" : "image").increment();
        score("forgery").record(forgery);
        score("explicit").record(explicit);
    }

    /** After commit, so a rolled-back decision is never counted. */
    @TransactionalEventListener(fallbackExecution = true)
    public void moderatorDecision(DecisionChangedEvent event) {
        if (event.current() == null) {
            return;
        }
        decisions("moderator", event.current(), "any").increment();
        if (event.previous() == Decision.AUTO_FLAG && event.current() != Decision.AUTO_FLAG) {
            Counter.builder("fmd.moderation.overturns")
                    .description("Automatic removals reversed by a moderator")
                    .register(registry).increment();
        }
    }

    public void slaEscalation(SlaStatus status, String jurisdiction, ComplaintType type) {
        String j = jurisdiction == null ? "unknown" : jurisdiction;
        String t = type == null ? "unknown" : type.name();
        Counter.builder("fmd.sla.escalations")
                .description("Complaints that became AT_RISK or BREACHED")
                .tags("status", status.name(), "jurisdiction", j, "type", t)
                .register(registry).increment();
        if (status == SlaStatus.BREACHED) {
            Counter.builder("fmd.sla.breaches")
                    .description("Complaints whose legal takedown deadline passed while open")
                    .tags("jurisdiction", j, "type", t)
                    .register(registry).increment();
        }
    }

    public void mlError(String endpoint, String reason) {
        Counter.builder("fmd.ml.client.errors")
                .description("Failed calls from the backend to the ML service")
                .tags("endpoint", endpoint, "reason", reason)
                .register(registry).increment();
    }

    public void rateLimited(String plan) {
        Counter.builder("fmd.api.rate.limited")
                .description("Partner API requests refused with 429")
                .tag("plan", plan == null ? "unknown" : plan)
                .register(registry).increment();
    }

    /** {@code linked}: the sighting matched at least one open investigation. */
    public void sighting(String source, boolean linked) {
        Counter.builder("fmd.sightings")
                .description("Sightings reported by partners or moderators")
                .tags("source", source, "linked", String.valueOf(linked))
                .register(registry).increment();
    }

    private Counter decisions(String source, Decision decision, String media) {
        return Counter.builder("fmd.decisions")
                .description("Routing decisions by source (model = automatic routing, moderator = human decision)")
                .tags("source", source, "decision", decision.name(), "media", media)
                .register(registry);
    }

    private DistributionSummary score(String model) {
        return DistributionSummary.builder("fmd.model.score")
                .description("Scores the models served")
                .tag("model", model)
                .serviceLevelObjectives(SCORE_BUCKETS)
                .register(registry);
    }

    private static double safe(java.util.function.LongSupplier count) {
        try {
            return count.getAsLong();
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }
}
