package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final int MAX_SIGNATURE_CHARS = 16000;

    private final MlClientService mlClientService;
    private final DecisionEngineService decisionEngineService;
    private final AnalysisResultRepository repository;
    private final DecisionThresholdProperties thresholds;
    private final SlaClockService slaClock;
    private final ProvenanceService provenance;
    private final PrescreenService prescreen;
    private final ApplicationEventPublisher events;

    public AnalysisService(
            MlClientService mlClientService,
            DecisionEngineService decisionEngineService,
            AnalysisResultRepository repository,
            DecisionThresholdProperties thresholds,
            SlaClockService slaClock,
            ProvenanceService provenance,
            PrescreenService prescreen,
            ApplicationEventPublisher events) {
        this.events = events;
        this.mlClientService = mlClientService;
        this.decisionEngineService = decisionEngineService;
        this.repository = repository;
        this.thresholds = thresholds;
        this.slaClock = slaClock;
        this.provenance = provenance;
        this.prescreen = prescreen;
    }

    @Transactional
    public AnalyzeOutcome analyze(byte[] imageBytes, String filename, String contentType) {
        return analyze(imageBytes, filename, contentType, null);
    }

    /** @param apiClientId the {@code /api/v1} client submitting the media, or null */
    @Transactional
    public AnalyzeOutcome analyze(byte[] imageBytes, String filename, String contentType, Long apiClientId) {
        MlInferResponse ml = mlClientService.infer(imageBytes, filename, contentType);
        if (ml == null) {
            throw new IllegalStateException("ML service returned empty response");
        }

        Decision decision = decisionEngineService.route(ml.getForgeryScore(), ml.getExplicitScore());

        AnalysisResult entity = new AnalysisResult();
        entity.setPhash(ml.getPhash() != null ? ml.getPhash() : "");
        entity.setForgeryScore(ml.getForgeryScore());
        entity.setExplicitScore(ml.getExplicitScore());
        entity.setDecision(decision);
        entity.setContentHash(ml.getContentHash());
        entity.setForensicSignature(signatureJson(ml));
        entity.setApiClientId(apiClientId);
        entity.setGenerationFamily(normalizeFamily(ml.getGenerationFamily()));
        entity.setFamilyConfidence(ml.getFamilyConfidence());
        entity.setJurisdiction(slaClock.normalizeJurisdiction(null));
        entity.setProvenanceId(
                ml.getProvenanceId() != null && !ml.getProvenanceId().isBlank() ? ml.getProvenanceId() : provenance.newId());

        ProvenanceService.SourceMatch source =
                provenance.trace(ml.getProvenance(), ml.getContentHash(), entity.getPhash());
        if (source != null && source.source() != null) {
            entity.setSourceProvenanceId(source.source().getProvenanceId());
        }
        AnalysisResult saved = repository.save(entity);
        provenance.recordAnalysis(saved, source);

        ProvenanceService.LabelledImage labelled = provenance.label(saved, imageBytes, filename, contentType);
        return new AnalyzeOutcome(saved, ml.getHeatmap(), labelled, provenance.matchView(source));
    }

    /**
     * Items awaiting a moderator, most urgent first: open complaints by nearest SLA deadline, then
     * everything else oldest first so nothing waits indefinitely behind newer uploads.
     */
    @Transactional(readOnly = true)
    public List<AnalysisResult> queue() {
        return repository.findByDecisionOrderByCreatedAtDesc(Decision.REVIEW).stream()
                .sorted(URGENCY)
                .toList();
    }

    static final Comparator<AnalysisResult> URGENCY = Comparator
            .comparing((AnalysisResult r) -> r.isComplianceOpen() && r.getSlaDeadline() != null ? r.getSlaDeadline() : null,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AnalysisResult::getCreatedAt);

    /** Most recent auto-flagged items (no moderator action needed, but reports may be). */
    @Transactional(readOnly = true)
    public List<AnalysisResult> flagged() {
        return repository.findTop50ByDecisionOrderByCreatedAtDesc(Decision.AUTO_FLAG);
    }

    private static String normalizeFamily(String family) {
        if (family == null) {
            return "unknown";
        }
        String f = family.trim().toLowerCase(java.util.Locale.ROOT);
        return f.equals("gan") || f.equals("diffusion") ? f : "unknown";
    }

    private static String signatureJson(MlInferResponse ml) {
        if (ml.getForensics() == null || ml.getForensics().isNull()) {
            return null;
        }
        String json = ml.getForensics().toString();
        if (json.length() > MAX_SIGNATURE_CHARS) {
            log.warn("Forensic signature too large ({} chars); not stored", json.length());
            return null;
        }
        return json;
    }

    @Transactional
    public AnalysisResult updateDecision(Long id, Decision decision) {
        AnalysisResult entity = repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found: " + id));
        Decision previous = entity.getDecision();
        boolean complaintWasOpen = entity.isComplianceOpen();
        entity.setDecision(decision);
        slaClock.stopClock(entity);
        AnalysisResult saved = repository.save(entity);
        provenance.decisionChanged(saved, previous, decision);
        if (decision == Decision.AUTO_FLAG && previous != Decision.AUTO_FLAG) {
            prescreen.confirmTakedown(saved.getPhash(), saved.getId());
        }
        if (complaintWasOpen) {
            provenance.slaResolved(saved, slaClock.remaining(saved));
        }
        events.publishEvent(new DecisionChangedEvent(saved.getId(), previous, decision));
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        long total = repository.count();
        Map<String, Long> byDecision = new LinkedHashMap<>();
        for (Decision d : Decision.values()) {
            byDecision.put(d.name(), repository.countByDecision(d));
        }
        Double avgForgery = repository.averageForgeryScore();
        Double avgExplicit = repository.averageExplicitScore();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("byDecision", byDecision);
        stats.put("avgForgeryScore", avgForgery != null ? avgForgery : 0.0);
        stats.put("avgExplicitScore", avgExplicit != null ? avgExplicit : 0.0);

        Map<String, Double> bands = new LinkedHashMap<>();
        bands.put("forgeryReview", thresholds.getForgeryReview());
        bands.put("forgeryAutoFlag", thresholds.getForgeryAutoFlag());
        bands.put("explicitReview", thresholds.getExplicitReview());
        bands.put("explicitAutoFlag", thresholds.getExplicitAutoFlag());
        stats.put("thresholds", bands);
        return stats;
    }

    /**
     * @param labelled copy of the upload with the provenance manifest embedded; null if labelling failed
     * @param embeddedSource where the upload was traced back to (manifest, pixel or pHash match); null if new
     */
    public record AnalyzeOutcome(
            AnalysisResult result,
            String heatmap,
            ProvenanceService.LabelledImage labelled,
            Map<String, Object> embeddedSource) {

        public AnalyzeOutcome withResult(AnalysisResult updated) {
            return new AnalyzeOutcome(updated, heatmap, labelled, embeddedSource);
        }
    }
}
