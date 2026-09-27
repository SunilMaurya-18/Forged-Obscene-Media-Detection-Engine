package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisService {

    private final MlClientService mlClientService;
    private final DecisionEngineService decisionEngineService;
    private final AnalysisResultRepository repository;
    private final DecisionThresholdProperties thresholds;
    private final SlaClockService slaClock;
    private final ProvenanceService provenance;

    public AnalysisService(
            MlClientService mlClientService,
            DecisionEngineService decisionEngineService,
            AnalysisResultRepository repository,
            DecisionThresholdProperties thresholds,
            SlaClockService slaClock,
            ProvenanceService provenance) {
        this.mlClientService = mlClientService;
        this.decisionEngineService = decisionEngineService;
        this.repository = repository;
        this.thresholds = thresholds;
        this.slaClock = slaClock;
        this.provenance = provenance;
    }

    @Transactional
    public AnalyzeOutcome analyze(byte[] imageBytes, String filename, String contentType) {
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

    @Transactional(readOnly = true)
    public List<AnalysisResult> queue() {
        return repository.findByDecisionOrderByCreatedAtDesc(Decision.REVIEW);
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
        if (complaintWasOpen) {
            provenance.slaResolved(saved, slaClock.remaining(saved));
        }
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
