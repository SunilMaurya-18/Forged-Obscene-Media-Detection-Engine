package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dev/demo only ({@code compliance.seed-demo=true}): inserts one complaint per SLA stage so the
 * compliance console has something to show on a fresh database. Scores are synthetic; no media involved.
 */
@Component
public class ComplianceDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComplianceDemoSeeder.class);

    private final ComplianceProperties properties;
    private final AnalysisResultRepository repository;
    private final ComplianceService complianceService;
    private final DecisionEngineService decisionEngine;
    private final ProvenanceService provenance;
    private final Clock clock;

    public ComplianceDemoSeeder(
            ComplianceProperties properties,
            AnalysisResultRepository repository,
            ComplianceService complianceService,
            DecisionEngineService decisionEngine,
            ProvenanceService provenance,
            Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.complianceService = complianceService;
        this.decisionEngine = decisionEngine;
        this.provenance = provenance;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSeedDemo() || repository.existsByComplaintTypeIsNotNull()) {
            return;
        }
        Instant now = clock.instant();
        Duration userWindow = properties.windowFor(ComplaintType.USER_COMPLAINT);
        Duration govWindow = properties.windowFor(ComplaintType.GOVERNMENT_ORDER);
        Duration atRisk = properties.getSla().getAtRiskThreshold();

        seed("seed00000000c001", 0.91, 0.12, ComplaintType.USER_COMPLAINT, "NCII-2026-0417", now.minus(userWindow.dividedBy(6)));
        seed("seed00000000c002", 0.64, 0.71, ComplaintType.USER_COMPLAINT, "NCII-2026-0412",
                now.minus(userWindow).plus(atRisk.dividedBy(2)));
        seed("seed00000000c003", 0.88, 0.05, ComplaintType.GOVERNMENT_ORDER, "MeitY/69A/2026/118",
                now.minus(govWindow.dividedBy(3)));
        seed("seed00000000c004", 0.77, 0.33, ComplaintType.GOVERNMENT_ORDER, "MeitY/69A/2026/103",
                now.minus(govWindow).minus(Duration.ofMinutes(12)));
        log.info("Seeded 4 demo compliance complaints (compliance.seed-demo=true)");
    }

    private void seed(String phash, double forgery, double explicit, ComplaintType type, String ref, Instant receivedAt) {
        AnalysisResult item = new AnalysisResult();
        item.setPhash(phash);
        item.setForgeryScore(forgery);
        item.setExplicitScore(explicit);
        item.setDecision(decisionEngine.route(forgery, explicit));
        item.setCreatedAt(receivedAt);
        item.setProvenanceId(provenance.newId());
        AnalysisResult saved = repository.save(item);
        provenance.recordAnalysis(saved, null);
        complianceService.openComplaint(saved, type, ref, receivedAt);
    }
}
