package com.forgeddetect.demo;

import com.forgeddetect.investigation.Investigation;
import com.forgeddetect.investigation.InvestigationService;
import com.forgeddetect.investigation.SightingService;
import com.forgeddetect.investigation.SightingSource;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ApiClient;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ApiClientRepository;
import com.forgeddetect.repository.AppealRepository;
import com.forgeddetect.security.DevModeratorProperties;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ApiKeyService;
import com.forgeddetect.service.AppealService;
import com.forgeddetect.service.CaseService;
import com.forgeddetect.service.ComplianceProperties;
import com.forgeddetect.service.ComplianceService;
import com.forgeddetect.service.ModerationAuditService;
import com.forgeddetect.service.ReportService;
import com.forgeddetect.service.SlaClockService;
import com.forgeddetect.state.SharedState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 25: seeds one complete, linked story through the real services, so a first-time visitor
 * lands on populated screens: a flagged upload taken down by a moderator, the uploader's appeal
 * against it, a signed evidentiary report, a victim's complaint close to its deadline and a
 * government order past it. All media is generated here from shapes; no photo is involved.
 */
@Service
public class DemoScenarioService {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioService.class);

    static final String LOCK = "lock:demo-story";
    private static final Duration LOCK_HOLD = Duration.ofMinutes(2);

    static final String FLAG_REASON = "Demo: blending seams and a spectral grid artefact around the inset panel; "
            + "consistent with a composited image.";
    static final String APPEAL_STATEMENT = "This is my own artwork. The inset was added in an editor on purpose "
            + "and nobody real is shown. Please restore it.";
    static final String GOVERNMENT_REFERENCE = "MeitY/69A/2026/DEMO";

    public static class DemoBusy extends ResponseStatusException {
        DemoBusy() {
            super(HttpStatus.CONFLICT, "The demo story is being prepared. Try again in a few seconds.");
        }
    }

    private final DemoProperties properties;
    private final DemoStoryRepository stories;
    private final AnalysisService analysis;
    private final ModerationAuditService audit;
    private final AppealService appeals;
    private final CaseService cases;
    private final ComplianceService compliance;
    private final ComplianceProperties complianceProperties;
    private final SlaClockService slaClock;
    private final ReportService reports;
    private final AnalysisResultRepository results;
    private final AppealRepository appealRepository;
    private final DevModeratorProperties moderators;
    private final InvestigationService investigations;
    private final SightingService sightings;
    private final ApiClientRepository apiClients;
    private final SharedState state;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String instance = UUID.randomUUID().toString();

    @PersistenceContext
    private EntityManager entityManager;

    public DemoScenarioService(
            DemoProperties properties,
            DemoStoryRepository stories,
            AnalysisService analysis,
            ModerationAuditService audit,
            AppealService appeals,
            CaseService cases,
            ComplianceService compliance,
            ComplianceProperties complianceProperties,
            SlaClockService slaClock,
            ReportService reports,
            AnalysisResultRepository results,
            AppealRepository appealRepository,
            DevModeratorProperties moderators,
            InvestigationService investigations,
            SightingService sightings,
            ApiClientRepository apiClients,
            SharedState state,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.properties = properties;
        this.stories = stories;
        this.analysis = analysis;
        this.audit = audit;
        this.appeals = appeals;
        this.cases = cases;
        this.compliance = compliance;
        this.complianceProperties = complianceProperties;
        this.slaClock = slaClock;
        this.reports = reports;
        this.results = results;
        this.appealRepository = appealRepository;
        this.moderators = moderators;
        this.investigations = investigations;
        this.sightings = sightings;
        this.apiClients = apiClients;
        this.state = state;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** The latest story whose items still exist (a manual cleanup or reset may have removed them). */
    public Optional<DemoStory> current() {
        return stories.findTopByOrderByIdDesc()
                .filter(s -> s.getFlaggedId() != null && results.existsById(s.getFlaggedId())
                        && s.getBreachedId() != null && results.existsById(s.getBreachedId()));
    }

    /** Returns the current story, seeding one first if there is none. Safe to call from every visitor. */
    public DemoStory ensure() {
        Optional<DemoStory> existing = current();
        if (existing.isPresent()) {
            return existing.get();
        }
        synchronized (this) {
            return current().orElseGet(() -> create(null));
        }
    }

    /**
     * Seeds a new story (even if one exists) in one transaction: a failure part-way, e.g. the ML
     * service being down, leaves nothing behind.
     *
     * @param resetAt carried onto the story when this follows a full reset
     * @throws DemoBusy while another request or instance is seeding
     */
    public synchronized DemoStory create(Instant resetAt) {
        if (!state.setIfAbsent(LOCK, instance, LOCK_HOLD)) {
            throw new DemoBusy();
        }
        try {
            DemoStory story = tx.execute(status -> seed(resetAt));
            log.info("Seeded demo story {} (flagged #{}, report {})", story.getId(), story.getFlaggedId(), story.getReportId());
            return story;
        } finally {
            state.delete(LOCK);
        }
    }

    private DemoStory seed(Instant resetAt) {
        Instant now = clock.instant();
        String jurisdiction = properties.getJurisdiction();
        String moderator = moderators.getModeratorUsername();
        long salt = new Random().nextLong();
        DemoStory story = new DemoStory();
        story.setCreatedAt(now);
        story.setResetAt(resetAt);

        // 1. An upload the moderator takes down. Recorded like ModerationService.decide, minus the
        //    wellbeing break check, so a visitor pausing the demo moderator can't block a reset.
        AnalysisService.AnalyzeOutcome upload = analysis.analyze(
                image(Pattern.COMPOSITE, salt), "demo-composite.jpg", "image/jpeg", null, jurisdiction);
        Long flaggedId = upload.result().getId();
        audit.record(moderator, ModerationAction.DECIDED, flaggedId, null,
                ModerationAuditService.decidedDetail(Decision.AUTO_FLAG, FLAG_REASON));
        analysis.updateDecision(flaggedId, Decision.AUTO_FLAG, moderator);
        story.setFlaggedId(flaggedId);
        story.setNoticeNumber(upload.notice().noticeNumber());
        story.setNoticePin(upload.notice().pin());

        // 2. The uploader appeals with the notice number and PIN from their takedown notice
        Appeal appeal = appeals.file(upload.notice().noticeNumber(), upload.notice().pin(), APPEAL_STATEMENT);
        story.setAppealId(appeal.getId());

        // 3. A signed report on the taken-down item, generated after the appeal so it covers it. The
        //    signature covers timestamps as the database stores them (microseconds), so sign what is read back.
        entityManager.flush();
        entityManager.clear();
        ReportService.Report report = reports.generate(flaggedId, null, null, null, moderator);
        story.setReportId(report.reportId());
        story.setReportSha256(report.sha256());
        story.setReportPdf(report.pdf());

        // 4. A victim's self-service report, received long enough ago to be inside the at-risk window
        Duration userWindow = slaClock.rule(jurisdiction, ComplaintType.USER_COMPLAINT).window();
        Duration atRisk = complianceProperties.getSla().getAtRiskThreshold();
        CaseService.Created filed = cases.file(new CaseService.Filing(
                image(Pattern.STRIPES, salt), "demo-report.jpg", "image/jpeg", "SELF",
                "Demo case: a synthetic pattern standing in for an intimate image shared without consent.",
                null, "en", jurisdiction), now.minus(userWindow).plus(atRisk.multipliedBy(9).dividedBy(10)));
        story.setAtRiskId(filed.item().getId());
        story.setCaseNumber(filed.complaint().getCaseNumber());
        story.setCasePin(filed.pin());

        // 5. A government order whose deadline has already passed (escalated on arrival)
        Duration govWindow = slaClock.rule(jurisdiction, ComplaintType.GOVERNMENT_ORDER).window();
        AnalysisService.AnalyzeOutcome order = compliance.fileComplaint(
                image(Pattern.BLOCKS, salt), "demo-order.jpg", "image/jpeg", ComplaintType.GOVERNMENT_ORDER,
                GOVERNMENT_REFERENCE, jurisdiction, now.minus(govWindow).minus(Duration.ofMinutes(12)));
        story.setBreachedId(order.result().getId());

        // 6. Where the media went: partner sightings on fictional platforms under made-up account IDs.
        //    One copy is reported before anyone opens a case, so opening it picks that sighting up.
        seedInvestigations(story, flaggedId, filed.item().getId(), moderator, now);

        return stories.save(story);
    }

    static final String DEMO_ACCOUNT = "demo-acct-orchid";

    private void seedInvestigations(DemoStory story, Long flaggedId, Long reportedId, String moderator, Instant now) {
        AnalysisResult flagged = results.findById(flaggedId).orElseThrow();
        AnalysisResult reported = results.findById(reportedId).orElseThrow();
        Long partner = apiClients.findFirstByNameIgnoreCase(ApiKeyService.DEMO_CLIENT).map(ApiClient::getId).orElse(null);

        sighting(flagged, 0, "chirp", "ch-4410982731", "demo-acct-lantern", now.minus(Duration.ofHours(30)), partner, null);
        Investigation spread = investigations.open(flaggedId, "Demo: composite reposted on partner platforms", moderator);
        sighting(flagged, 3, "pixelgram", "pg-7Q2X91", DEMO_ACCOUNT, now.minus(Duration.ofHours(20)), partner, null);
        sighting(flagged, 1, "vidshare", "vs-000318", "demo-acct-heron", now.minus(Duration.ofHours(6)), partner, null);
        story.setInvestigationNumber(spread.getCaseNumber());

        Investigation victim = investigations.open(reportedId, "Demo: victim report, source account", moderator);
        sighting(reported, 0, "pixelgram", "pg-8K3M02", DEMO_ACCOUNT, now.minus(Duration.ofHours(2)), null, moderator);
        investigations.note(victim.getCaseNumber(),
                "Demo: the reporter named this post. The same pixelgram account also posted the composite in "
                        + spread.getCaseNumber() + ".", moderator);
        story.setRelatedInvestigationNumber(victim.getCaseNumber());
    }

    /** A re-encoded copy: {@code flips} low bits of the hash differ, as they would after recompression. */
    private void sighting(AnalysisResult item, int flips, String platform, String postRef, String account,
            Instant observedAt, Long partnerId, String moderator) {
        String phash = item.getPhash();
        boolean hashed = phash != null && phash.matches("[0-9a-f]{16}");
        String hash = hashed ? String.format("%016x", Long.parseUnsignedLong(phash, 16) ^ ((1L << flips) - 1)) : null;
        if (hash == null && item.getProvenanceId() == null) {
            return;
        }
        sightings.report(
                new SightingService.Report(hash, hashed ? null : item.getProvenanceId(), platform, postRef, account, observedAt.toString()),
                moderator != null ? SightingSource.MODERATOR : SightingSource.PARTNER_API, partnerId, moderator);
    }

    /** Live view of a story: statuses come from the records now, not from when it was seeded. */
    public Map<String, Object> view(DemoStory s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("createdAt", s.getCreatedAt().toString());
        out.put("resetAt", s.getResetAt() != null ? s.getResetAt().toString() : null);

        results.findById(s.getFlaggedId()).ifPresent(item -> {
            Map<String, Object> flagged = item(item);
            flagged.put("noticeNumber", s.getNoticeNumber());
            flagged.put("pin", s.getNoticePin());
            flagged.put("reason", FLAG_REASON);
            out.put("flagged", flagged);
        });
        if (s.getAppealId() != null) {
            appealRepository.findById(s.getAppealId()).ifPresent(a -> {
                Map<String, Object> appeal = new LinkedHashMap<>();
                appeal.put("caseNumber", a.getCaseNumber());
                appeal.put("pin", s.getNoticePin());
                appeal.put("status", a.getStatus().name());
                appeal.put("contestedBy", a.getContestedBy());
                appeal.put("filedAt", a.getFiledAt().toString());
                appeal.put("slaDeadline", a.getSlaDeadline() != null ? a.getSlaDeadline().toString() : null);
                appeal.put("slaStatus", a.getSlaStatus() != null ? a.getSlaStatus().name() : null);
                appeal.put("statement", a.getStatement());
                out.put("appeal", appeal);
            });
        }
        if (s.getReportId() != null) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("reportId", s.getReportId());
            report.put("sha256", s.getReportSha256());
            report.put("analysisId", s.getFlaggedId());
            report.put("pdfUrl", "/api/demo/report.pdf");
            out.put("report", report);
        }
        if (s.getAtRiskId() != null) {
            results.findById(s.getAtRiskId()).ifPresent(item -> {
                Map<String, Object> atRisk = item(item);
                atRisk.put("caseNumber", s.getCaseNumber());
                atRisk.put("pin", s.getCasePin());
                out.put("atRisk", atRisk);
            });
        }
        results.findById(s.getBreachedId()).ifPresent(item -> out.put("breached", item(item)));
        if (s.getInvestigationNumber() != null) {
            investigations.summary(s.getInvestigationNumber()).ifPresent(m -> out.put("investigation", m));
        }
        if (s.getRelatedInvestigationNumber() != null) {
            investigations.summary(s.getRelatedInvestigationNumber()).ifPresent(m -> out.put("relatedInvestigation", m));
        }
        return out;
    }

    private static Map<String, Object> item(AnalysisResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("provenanceId", r.getProvenanceId());
        m.put("decision", r.getDecision().name());
        m.put("modelDecision", r.getModelDecision() != null ? r.getModelDecision().name() : null);
        m.put("decidedBy", r.getDecidedBy());
        m.put("forgeryScore", r.getForgeryScore());
        m.put("explicitScore", r.getExplicitScore());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        m.put("complaintType", r.getComplaintType() != null ? r.getComplaintType().name() : null);
        m.put("reference", r.getComplaintReference());
        m.put("receivedAt", r.getComplaintReceivedAt() != null ? r.getComplaintReceivedAt().toString() : null);
        m.put("slaDeadline", r.getSlaDeadline() != null ? r.getSlaDeadline().toString() : null);
        m.put("slaStatus", r.getSlaStatus() != null ? r.getSlaStatus().name() : null);
        m.put("slaRule", r.getSlaRuleLabel());
        m.put("escalatedAt", r.getEscalatedAt() != null ? r.getEscalatedAt().toString() : null);
        m.put("resolvedAt", r.getResolvedAt() != null ? r.getResolvedAt().toString() : null);
        m.put("appealStatus", r.getAppealStatus());
        return m;
    }

    // ------------------------------------------------------------------ synthetic media

    enum Pattern { COMPOSITE, STRIPES, BLOCKS }

    /** Abstract shapes only (no text, so no fonts are needed on a headless server). */
    static byte[] image(Pattern pattern, long salt) {
        Random rnd = new Random(salt ^ pattern.ordinal() * 0x9E3779B97F4A7C15L);
        int w = 512;
        int h = 384;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        switch (pattern) {
            case COMPOSITE -> {
                g.setPaint(new GradientPaint(0, 0, new Color(0x1E1B4B), w, h, new Color(0x7C3AED)));
                g.fillRect(0, 0, w, h);
                g.setStroke(new BasicStroke(6f));
                for (int r = 30; r < 420; r += 28) {
                    g.setColor(new Color(0x22D3EE));
                    g.draw(new Ellipse2D.Double(150 - r / 2.0, 190 - r / 2.0, r, r));
                }
                // A pasted-in panel with its own texture and a hard seam: the "edit" the models look for
                int px = 280 + rnd.nextInt(40);
                int py = 70 + rnd.nextInt(40);
                for (int y = 0; y < 180; y += 4) {
                    for (int x = 0; x < 160; x += 4) {
                        g.setColor(new Color(200 + rnd.nextInt(56), 90 + rnd.nextInt(60), 60 + rnd.nextInt(40)));
                        g.fillRect(px + x, py + y, 4, 4);
                    }
                }
                g.setColor(new Color(0xFDE047));
                g.fill(new Ellipse2D.Double(px + 40, py + 50, 80, 80));
            }
            case STRIPES -> {
                int period = 18 + rnd.nextInt(6);
                Color[] palette = {new Color(0xF97316), new Color(0x2563EB), new Color(0xFACC15), new Color(0x0F172A)};
                for (int x = -h; x < w; x += period) {
                    g.setColor(palette[Math.floorMod(x / period, palette.length)]);
                    g.fillPolygon(new int[] {x, x + period, x + period + h, x + h}, new int[] {0, 0, h, h}, 4);
                }
            }
            case BLOCKS -> {
                int cell = 48 + rnd.nextInt(8);
                for (int y = 0; y < h; y += cell) {
                    for (int x = 0; x < w; x += cell) {
                        boolean dark = ((x / cell) + (y / cell)) % 2 == 0;
                        g.setColor(dark ? new Color(0x064E3B) : new Color(0x34D399));
                        g.fillRect(x, y, cell, cell);
                    }
                }
                g.setColor(new Color(0xF43F5E));
                g.fillRect(w / 3 + rnd.nextInt(20), h / 4, w / 3, h / 2);
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
