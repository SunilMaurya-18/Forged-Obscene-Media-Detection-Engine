package com.forgeddetect.demo;

import com.forgeddetect.investigation.EvidencePackageRepository;
import com.forgeddetect.investigation.InvestigationEventRepository;
import com.forgeddetect.investigation.InvestigationItemRepository;
import com.forgeddetect.investigation.InvestigationRepository;
import com.forgeddetect.investigation.LegalHoldRepository;
import com.forgeddetect.investigation.LegalRequestRepository;
import com.forgeddetect.investigation.PreservationRepository;
import com.forgeddetect.investigation.SightingLinkRepository;
import com.forgeddetect.investigation.SightingRepository;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ApiClientRepository;
import com.forgeddetect.repository.AppealRepository;
import com.forgeddetect.repository.ComplaintRepository;
import com.forgeddetect.repository.KnownHashRepository;
import com.forgeddetect.repository.ModerationAuditEventRepository;
import com.forgeddetect.repository.ProvenanceEventRepository;
import com.forgeddetect.service.ApiKeyService;
import com.forgeddetect.service.ComplianceDemoSeeder;
import com.forgeddetect.service.PrescreenService;
import com.forgeddetect.service.TransparencyService;
import com.forgeddetect.state.SharedState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 25: puts a demo instance back to its first-visit state. Deletes every item, complaint, appeal,
 * audit event, investigation (with its preservations and legal holds), sighting, known hash and partner key, keeps moderator accounts, jurisdiction rules and signing
 * keys, then re-runs the startup seeders and seeds a new story. Only reachable while
 * {@code demo.enabled=true}, which the backend refuses outside the dev profile.
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    static final String LOCK = "lock:demo-reset";
    static final String COOLDOWN = "demo:reset-cooldown";
    private static final Duration LOCK_HOLD = Duration.ofMinutes(5);

    public static class CoolingDown extends ResponseStatusException {
        private final long retryAfterSeconds;

        CoolingDown(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "The demo was reset moments ago. Try again in " + retryAfterSeconds + " seconds.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final DemoProperties properties;
    private final DemoScenarioService scenario;
    private final DemoStoryRepository stories;
    private final AppealRepository appeals;
    private final ModerationAuditEventRepository auditEvents;
    private final ComplaintRepository complaints;
    private final ProvenanceEventRepository provenanceEvents;
    private final SightingLinkRepository sightingLinks;
    private final InvestigationEventRepository investigationEvents;
    private final InvestigationItemRepository investigationItems;
    private final SightingRepository sightings;
    private final InvestigationRepository investigations;
    private final PreservationRepository preservations;
    private final LegalHoldRepository legalHolds;
    private final LegalRequestRepository legalRequests;
    private final EvidencePackageRepository evidencePackages;
    private final AnalysisResultRepository results;
    private final KnownHashRepository knownHashes;
    private final ApiClientRepository apiClients;
    private final PrescreenService prescreen;
    private final ApiKeyService apiKeys;
    private final ComplianceDemoSeeder complianceSeeder;
    private final TransparencyService transparency;
    private final SharedState state;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String instance = UUID.randomUUID().toString();

    public DemoResetService(
            DemoProperties properties,
            DemoScenarioService scenario,
            DemoStoryRepository stories,
            AppealRepository appeals,
            ModerationAuditEventRepository auditEvents,
            ComplaintRepository complaints,
            ProvenanceEventRepository provenanceEvents,
            SightingLinkRepository sightingLinks,
            InvestigationEventRepository investigationEvents,
            InvestigationItemRepository investigationItems,
            SightingRepository sightings,
            InvestigationRepository investigations,
            PreservationRepository preservations,
            LegalHoldRepository legalHolds,
            LegalRequestRepository legalRequests,
            EvidencePackageRepository evidencePackages,
            AnalysisResultRepository results,
            KnownHashRepository knownHashes,
            ApiClientRepository apiClients,
            PrescreenService prescreen,
            ApiKeyService apiKeys,
            ComplianceDemoSeeder complianceSeeder,
            TransparencyService transparency,
            SharedState state,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.properties = properties;
        this.scenario = scenario;
        this.stories = stories;
        this.appeals = appeals;
        this.auditEvents = auditEvents;
        this.complaints = complaints;
        this.provenanceEvents = provenanceEvents;
        this.sightingLinks = sightingLinks;
        this.investigationEvents = investigationEvents;
        this.investigationItems = investigationItems;
        this.sightings = sightings;
        this.investigations = investigations;
        this.preservations = preservations;
        this.legalHolds = legalHolds;
        this.legalRequests = legalRequests;
        this.evidencePackages = evidencePackages;
        this.results = results;
        this.knownHashes = knownHashes;
        this.apiClients = apiClients;
        this.prescreen = prescreen;
        this.apiKeys = apiKeys;
        this.complianceSeeder = complianceSeeder;
        this.transparency = transparency;
        this.state = state;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * @param visitor an anonymous or non-senior caller in public mode: subject to {@code demo.reset-cooldown}
     * @throws CoolingDown if a visitor reset ran within the cooldown
     * @throws DemoScenarioService.DemoBusy if a reset is already running
     */
    public DemoStory reset(boolean visitor) {
        if (visitor && !state.setIfAbsent(COOLDOWN, "1", properties.getResetCooldown())) {
            long wait = state.timeToLive(COOLDOWN).map(Duration::toSeconds).orElse(properties.getResetCooldown().toSeconds());
            throw new CoolingDown(Math.max(1, wait));
        }
        if (!state.setIfAbsent(LOCK, instance, LOCK_HOLD)) {
            throw new DemoScenarioService.DemoBusy();
        }
        try {
            Instant now = clock.instant();
            tx.executeWithoutResult(s -> {
                stories.deleteAllInBatch();
                appeals.deleteAllInBatch();
                auditEvents.deleteAllInBatch();
                complaints.deleteAllInBatch();
                provenanceEvents.deleteAllInBatch();
                sightingLinks.deleteAllInBatch();
                evidencePackages.deleteAllInBatch();
                // Row by row so the request's case list goes with it
                legalRequests.deleteAll();
                preservations.deleteAllInBatch();
                legalHolds.deleteAllInBatch();
                investigationEvents.deleteAllInBatch();
                investigationItems.deleteAllInBatch();
                sightings.deleteAllInBatch();
                investigations.deleteAllInBatch();
                results.deleteAllInBatch();
                knownHashes.deleteAllInBatch();
                apiClients.deleteAllInBatch();
            });
            tx.executeWithoutResult(s -> {
                prescreen.seed();
                apiKeys.seedDemoClient();
                complianceSeeder.run(null);
            });
            transparency.clearCache();
            DemoStory story = scenario.create(now);
            log.info("Demo reset complete ({})", visitor ? "visitor" : "scheduled or senior moderator");
            return story;
        } finally {
            state.delete(LOCK);
        }
    }

    /** When the current data was last reset (or first seeded). */
    public Optional<Instant> lastReset() {
        return stories.findTopByOrderByIdDesc().map(s -> s.getResetAt() != null ? s.getResetAt() : s.getCreatedAt());
    }

    public Optional<Instant> nextReset() {
        if (!properties.scheduledReset()) {
            return Optional.empty();
        }
        return lastReset().map(t -> t.plus(properties.getResetInterval()));
    }

    @Scheduled(fixedDelayString = "${demo.reset-check:PT1M}", initialDelayString = "${demo.reset-check:PT1M}")
    public void scheduled() {
        Optional<Instant> due = nextReset();
        if (due.isEmpty() || clock.instant().isBefore(due.get())) {
            return;
        }
        try {
            reset(false);
        } catch (DemoScenarioService.DemoBusy busy) {
            // Another instance is resetting
        } catch (RuntimeException ex) {
            log.warn("Scheduled demo reset failed; retrying on the next check: {}", ex.getMessage());
        }
    }
}
