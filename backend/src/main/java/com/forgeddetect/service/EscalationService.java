package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.ProvenanceEventType;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.observability.FmdMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Raises an alert when an open complaint or appeal becomes AT_RISK or BREACHED.
 * Always logs; additionally POSTs JSON to {@code compliance.escalation.webhook-url} when configured.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final ComplianceProperties properties;
    private final WebClient webClient;
    private final ProvenanceService provenance;
    private final Clock clock;
    private final FmdMetrics metrics;

    public EscalationService(
            ComplianceProperties properties,
            WebClient.Builder webClientBuilder,
            ProvenanceService provenance,
            Clock clock,
            FmdMetrics metrics) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.provenance = provenance;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void escalate(AnalysisResult item, SlaStatus status) {
        Duration remaining = Duration.between(clock.instant(), item.getSlaDeadline());
        item.setEscalatedAt(clock.instant());
        metrics.slaEscalation(status, item.getJurisdiction(), item.getComplaintType());

        log.warn(
                "SLA ESCALATION [{}] analysis #{} ({}{}) deadline={} remaining={}",
                status,
                item.getId(),
                item.getComplaintType(),
                item.getComplaintReference() != null ? " ref " + item.getComplaintReference() : "",
                item.getSlaDeadline(),
                remaining);
        provenance.escalated(item, status, remaining);

        String url = properties.getEscalation().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "SLA_" + status.name());
        payload.put("analysisId", item.getId());
        payload.put("complaintType", item.getComplaintType().name());
        payload.put("complaintReference", item.getComplaintReference());
        payload.put("slaDeadline", item.getSlaDeadline().toString());
        payload.put("remainingSeconds", remaining.toSeconds());
        post(url, payload, item.getId());
    }

    /** Same alert path for an uploader's appeal whose decision clock became AT_RISK or BREACHED. */
    public void escalateAppeal(Appeal appeal, AnalysisResult item, SlaStatus status) {
        Instant now = clock.instant();
        Duration remaining = Duration.between(now, appeal.getSlaDeadline());
        appeal.setEscalatedAt(now);
        metrics.slaEscalation(status, item != null ? item.getJurisdiction() : null, ComplaintType.APPEAL);
        log.warn("APPEAL SLA ESCALATION [{}] appeal {} (analysis #{}) deadline={} remaining={}",
                status, appeal.getCaseNumber(), appeal.getAnalysisId(), appeal.getSlaDeadline(), remaining);
        if (item != null) {
            provenance.record(item.getProvenanceId(), item.getId(), ProvenanceEventType.SLA_ESCALATED, ProvenanceService.SYSTEM,
                    status == SlaStatus.BREACHED
                            ? "Appeal SLA breached, " + ProvenanceService.human(remaining) + " overdue; escalated"
                            : "Appeal SLA at risk, " + ProvenanceService.human(remaining) + " left; escalated",
                    now);
        }

        String url = properties.getEscalation().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "APPEAL_SLA_" + status.name());
        payload.put("analysisId", appeal.getAnalysisId());
        payload.put("complaintType", ComplaintType.APPEAL.name());
        payload.put("appealCaseNumber", appeal.getCaseNumber());
        payload.put("slaDeadline", appeal.getSlaDeadline().toString());
        payload.put("remainingSeconds", remaining.toSeconds());
        post(url, payload, appeal.getAnalysisId());
    }

    private void post(String url, Map<String, Object> payload, Long analysisId) {
        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        ok -> log.info("Escalation webhook delivered for analysis #{}", analysisId),
                        err -> log.error("Escalation webhook failed for analysis #{}: {}", analysisId, err.getMessage()));
    }
}
