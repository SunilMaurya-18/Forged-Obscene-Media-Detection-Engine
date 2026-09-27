package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceService {

    private final AnalysisService analysisService;
    private final SlaClockService slaClock;
    private final EscalationService escalationService;
    private final AnalysisResultRepository repository;
    private final ComplianceProperties properties;
    private final ProvenanceService provenance;
    private final Clock clock;

    public ComplianceService(
            AnalysisService analysisService,
            SlaClockService slaClock,
            EscalationService escalationService,
            AnalysisResultRepository repository,
            ComplianceProperties properties,
            ProvenanceService provenance,
            Clock clock) {
        this.analysisService = analysisService;
        this.slaClock = slaClock;
        this.escalationService = escalationService;
        this.repository = repository;
        this.properties = properties;
        this.provenance = provenance;
        this.clock = clock;
    }

    /** Analyzes the reported media and starts its takedown clock now. */
    @Transactional
    public AnalysisService.AnalyzeOutcome fileComplaint(
            byte[] imageBytes, String filename, String contentType, ComplaintType type, String reference) {
        AnalysisService.AnalyzeOutcome outcome = analysisService.analyze(imageBytes, filename, contentType);
        AnalysisResult saved = openComplaint(outcome.result(), type, reference, clock.instant());
        return outcome.withResult(saved);
    }

    /**
     * Starts the clock from {@code receivedAt} (may be in the past, e.g. for imported or seeded complaints)
     * and escalates immediately if the item is already at risk or breached.
     */
    @Transactional
    public AnalysisResult openComplaint(AnalysisResult item, ComplaintType type, String reference, Instant receivedAt) {
        slaClock.startClock(item, type, reference, receivedAt);
        AnalysisResult saved = repository.save(item);
        provenance.complaintOpened(saved, type, properties.windowFor(type), receivedAt);
        if (saved.getSlaStatus() != SlaStatus.ON_TRACK) {
            escalationService.escalate(saved, saved.getSlaStatus());
            saved = repository.save(saved);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AnalysisResult> openQueue() {
        return repository.findByComplaintTypeIsNotNullAndResolvedAtIsNullOrderBySlaDeadlineAsc();
    }

    /** Every complaint that missed its deadline, open or resolved late. */
    @Transactional(readOnly = true)
    public List<AnalysisResult> breaches() {
        return repository.findBySlaStatusOrderBySlaDeadlineAsc(SlaStatus.BREACHED);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Long> open = new LinkedHashMap<>();
        long total = 0;
        for (SlaStatus s : SlaStatus.values()) {
            long n = repository.countByComplaintTypeIsNotNullAndResolvedAtIsNullAndSlaStatus(s);
            open.put(s.name(), n);
            total += n;
        }
        Map<String, Long> windows = new LinkedHashMap<>();
        for (ComplaintType t : ComplaintType.values()) {
            windows.put(t.name(), properties.windowFor(t).toMinutes());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open", total);
        body.put("byStatus", open);
        body.put("totalBreaches", repository.findBySlaStatusOrderBySlaDeadlineAsc(SlaStatus.BREACHED).size());
        body.put("slaMinutes", windows);
        body.put("atRiskMinutes", properties.getSla().getAtRiskThreshold().toMinutes());
        body.put("serverTime", clock.instant().toString());
        return body;
    }
}
