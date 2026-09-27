package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Complaint;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ComplaintRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Phase 21 notification hook (demo stub): tells the reporter when their case changes stage.
 * Always logs a {@code CASE NOTIFY} line with the contact masked; when {@code complaints.notify-webhook-url}
 * is set it also POSTs a JSON event there, which is where a real email/SMS provider would be attached.
 */
@Service
public class CaseNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CaseNotificationService.class);

    private static final Map<String, Map<CaseService.Stage, String>> MESSAGES = Map.of(
            "en", Map.of(
                    CaseService.Stage.IN_REVIEW, "We received your report %s. A moderator is reviewing it.",
                    CaseService.Stage.REMOVED, "Update on report %s: the content was removed.",
                    CaseService.Stage.NO_ACTION, "Update on report %s: we reviewed it and did not remove the content."),
            "hi", Map.of(
                    CaseService.Stage.IN_REVIEW, "हमें आपकी रिपोर्ट %s मिल गई है। एक मॉडरेटर इसकी जाँच कर रहा है।",
                    CaseService.Stage.REMOVED, "रिपोर्ट %s पर अपडेट: सामग्री हटा दी गई है।",
                    CaseService.Stage.NO_ACTION, "रिपोर्ट %s पर अपडेट: हमने इसकी जाँच की और सामग्री नहीं हटाई।"));

    private final ComplaintRepository complaints;
    private final AnalysisResultRepository results;
    private final CaseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public CaseNotificationService(
            ComplaintRepository complaints,
            AnalysisResultRepository results,
            CaseProperties properties,
            WebClient.Builder webClientBuilder,
            Clock clock) {
        this.complaints = complaints;
        this.results = results;
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.clock = clock;
    }

    /** Fires once per stage change; repeated calls with the same stage are ignored. */
    public void statusChanged(Complaint c, AnalysisResult item, CaseService.Stage stage) {
        if (stage.name().equals(c.getLastNotifiedStage())) {
            return;
        }
        c.setLastNotifiedStage(stage.name());
        c.setLastNotifiedAt(clock.instant());
        complaints.save(c);

        String message = MESSAGES.getOrDefault(c.getLocale(), MESSAGES.get("en")).get(stage).formatted(c.getCaseNumber());
        if (c.getContact() == null) {
            log.info("CASE NOTIFY [none] case {} -> {} (no contact given)", c.getCaseNumber(), stage);
            return;
        }
        log.info("CASE NOTIFY [{}] to {} case {} -> {}: {}",
                c.getContactChannel(), mask(c.getContact()), c.getCaseNumber(), stage, message);

        String url = properties.getNotifyWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "CASE_STATUS_CHANGED");
        payload.put("caseNumber", c.getCaseNumber());
        payload.put("stage", stage.name());
        payload.put("channel", c.getContactChannel());
        payload.put("to", c.getContact());
        payload.put("locale", c.getLocale());
        payload.put("message", message);
        payload.put("analysisId", item.getId());
        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        ok -> log.info("Case notification delivered for {}", c.getCaseNumber()),
                        err -> log.error("Case notification failed for {}: {}", c.getCaseNumber(), err.getMessage()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDecisionChanged(DecisionChangedEvent event) {
        complaints.findByAnalysisId(event.analysisId()).ifPresent(c -> results.findById(c.getAnalysisId())
                .ifPresent(item -> statusChanged(c, item, CaseService.stage(item))));
    }

    /** {@code r***@example.com}, {@code +91******3210}: enough to recognise in logs, not enough to reuse. */
    static String mask(String contact) {
        int at = contact.indexOf('@');
        if (at > 0) {
            return contact.charAt(0) + "***" + contact.substring(at);
        }
        int keep = Math.min(4, contact.length());
        String head = contact.startsWith("+") ? contact.substring(0, Math.min(3, contact.length())) : "";
        int stars = Math.max(0, contact.length() - head.length() - keep);
        return head + "*".repeat(stars) + contact.substring(contact.length() - keep);
    }
}
