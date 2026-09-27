package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.SlaStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Raises an alert when an open complaint becomes AT_RISK or BREACHED.
 * Always logs; additionally POSTs JSON to {@code compliance.escalation.webhook-url} when configured.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final ComplianceProperties properties;
    private final WebClient webClient;
    private final ProvenanceService provenance;
    private final Clock clock;

    public EscalationService(
            ComplianceProperties properties,
            WebClient.Builder webClientBuilder,
            ProvenanceService provenance,
            Clock clock) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.provenance = provenance;
        this.clock = clock;
    }

    public void escalate(AnalysisResult item, SlaStatus status) {
        Duration remaining = Duration.between(clock.instant(), item.getSlaDeadline());
        item.setEscalatedAt(clock.instant());

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

        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        ok -> log.info("Escalation webhook delivered for analysis #{}", item.getId()),
                        err -> log.error("Escalation webhook failed for analysis #{}: {}", item.getId(), err.getMessage()));
    }
}
