package com.forgeddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.observability.FmdMetrics;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumMap;
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
    private static final int MAX_C2PA_CHARS = 4000;
    private static final int MAX_TIMELINE_FRAMES = 240;

    private final MlClientService mlClientService;
    private final DecisionEngineService decisionEngineService;
    private final AnalysisResultRepository repository;
    private final DecisionThresholdProperties thresholds;
    private final SlaClockService slaClock;
    private final ProvenanceService provenance;
    private final PrescreenService prescreen;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final VideoProperties videoProps;
    private final NoticeService notices;
    private final ObjectMapper mapper;
    private final FmdMetrics metrics;

    public AnalysisService(
            MlClientService mlClientService,
            DecisionEngineService decisionEngineService,
            AnalysisResultRepository repository,
            DecisionThresholdProperties thresholds,
            SlaClockService slaClock,
            ProvenanceService provenance,
            PrescreenService prescreen,
            ApplicationEventPublisher events,
            Clock clock,
            VideoProperties videoProps,
            NoticeService notices,
            ObjectMapper mapper,
            FmdMetrics metrics) {
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
        this.videoProps = videoProps;
        this.notices = notices;
        this.mapper = mapper;
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

    /**
     * Images go to {@code /infer}; videos to {@code /infer-video}, which scores sampled frames in memory.
     * A video is routed once, on the configured aggregates (default: forgery top-k mean, explicit max).
     *
     * @param apiClientId the {@code /api/v1} client submitting the media, or null
     */
    @Transactional
    public AnalyzeOutcome analyze(byte[] imageBytes, String filename, String contentType, Long apiClientId) {
        return analyze(imageBytes, filename, contentType, apiClientId, null);
    }

    /**
     * @param jurisdiction explicit jurisdiction code for this upload (governs its appeal clock); null =
     *     platform default. Never inferred from the request's IP.
     * @throws IllegalArgumentException for a malformed jurisdiction code
     */
    @Transactional
    public AnalyzeOutcome analyze(byte[] imageBytes, String filename, String contentType, Long apiClientId, String jurisdiction) {
        String j = slaClock.normalizeJurisdiction(jurisdiction);
        boolean video = MediaKinds.isVideo(contentType, filename, imageBytes);
        if (!video && imageBytes.length > MediaKinds.IMAGE_MAX_BYTES) {
            throw new MediaTooLargeException("Image is " + imageBytes.length + " bytes; the limit is " + MediaKinds.IMAGE_MAX_BYTES);
        }
        MlInferResponse ml = video
                ? mlClientService.inferVideo(imageBytes, filename, contentType, videoProps.getFps(), videoProps.getTopK())
                : mlClientService.infer(imageBytes, filename, contentType);
        if (ml == null) {
            throw new IllegalStateException("ML service returned empty response");
        }
        video = video || ml.isVideo();

        double forgery = video
                ? VideoProperties.pick(ml.getAggregate(), "forgery", videoProps.getForgeryAggregate(), ml.getForgeryScore())
                : ml.getForgeryScore();
        double explicit = video
                ? VideoProperties.pick(ml.getAggregate(), "explicit", videoProps.getExplicitAggregate(), ml.getExplicitScore())
                : ml.getExplicitScore();
        Decision decision = decisionEngineService.route(forgery, explicit);
        metrics.modelDecision(decision, video, forgery, explicit);

        AnalysisResult entity = new AnalysisResult();
        entity.setPhash(ml.getPhash() != null ? ml.getPhash() : "");
        entity.setForgeryScore(forgery);
        entity.setExplicitScore(explicit);
        entity.setMediaType(video ? "VIDEO" : "IMAGE");
        entity.setFrameTimeline(video ? timelineJson(ml) : null);
        entity.setC2paManifest(c2paJson(ml.getC2pa()));
        entity.setDecision(decision);
        entity.setModelDecision(decision);
        entity.setContentHash(ml.getContentHash());
        entity.setForensicSignature(signatureJson(ml));
        entity.setApiClientId(apiClientId);
        entity.setGenerationFamily(normalizeFamily(ml.getGenerationFamily()));
        entity.setFamilyConfidence(ml.getFamilyConfidence());
        entity.setJurisdiction(j);
        entity.setProvenanceId(
                ml.getProvenanceId() != null && !ml.getProvenanceId().isBlank() ? ml.getProvenanceId() : provenance.newId());

        ProvenanceService.SourceMatch source =
                provenance.trace(ml.getProvenance(), ml.getContentHash(), entity.getPhash());
        if (source != null && source.source() != null) {
            entity.setSourceProvenanceId(source.source().getProvenanceId());
        }
        NoticeService.Notice notice = notices.issue(entity);
        AnalysisResult saved = repository.save(entity);
        provenance.recordAnalysis(saved, source);

        ProvenanceService.LabelledImage labelled =
                video ? null : provenance.label(saved, imageBytes, filename, contentType);
        if (labelled != null && labelled.base64() != null) {
            events.publishEvent(new LabelledCopyIssued(labelled.base64()));
        }
        return new AnalyzeOutcome(saved, ml.getHeatmap(), labelled, provenance.matchView(source), notice);
    }

    /**
     * Compact per-frame record for the report strip: {@code frames} is [t, forgery, explicit] triples.
     * Scores only; no pixels leave the ML service except the peak frame's heatmap.
     */
    private String timelineJson(MlInferResponse ml) {
        ObjectNode out = mapper.createObjectNode();
        JsonNode info = ml.getFrames();
        out.put("fps", info != null && info.has("fps") ? info.get("fps").asDouble() : videoProps.getFps());
        out.put("k", ml.getAggregate() != null && ml.getAggregate().has("k") ? ml.getAggregate().get("k").asInt() : videoProps.getTopK());
        if (info != null) {
            copy(info, out, "sampled", "sampled");
            copy(info, out, "truncated", "truncated");
            copy(info, out, "duration_seconds", "durationSeconds");
            copy(info, out, "width", "width");
            copy(info, out, "height", "height");
            copy(info, out, "codec", "codec");
            copy(info, out, "source_fps", "sourceFps");
        }
        if (ml.getPeak() != null && !ml.getPeak().isNull()) {
            out.set("peak", ml.getPeak());
        }
        ObjectNode routing = out.putObject("routing");
        routing.put("forgery", videoProps.getForgeryAggregate().name());
        routing.put("explicit", videoProps.getExplicitAggregate().name());
        if (ml.getAggregate() != null) {
            ObjectNode agg = out.putObject("aggregate");
            for (String score : List.of("forgery", "explicit")) {
                JsonNode block = ml.getAggregate().get(score);
                if (block != null) {
                    ObjectNode b = agg.putObject(score);
                    b.put("max", round(block.path("max").asDouble()));
                    b.put("topkMean", round(block.path("topk_mean").asDouble()));
                }
            }
        }
        ArrayNode frames = out.putArray("frames");
        if (ml.getTimeline() != null) {
            for (JsonNode f : ml.getTimeline()) {
                if (frames.size() >= MAX_TIMELINE_FRAMES) {
                    out.put("timelineClipped", true);
                    break;
                }
                frames.addArray()
                        .add(round(f.path("t").asDouble()))
                        .add(round(f.path("forgery_score").asDouble()))
                        .add(round(f.path("explicit_score").asDouble()));
            }
        }
        return out.toString();
    }

    private static void copy(JsonNode from, ObjectNode to, String src, String dst) {
        JsonNode v = from.get(src);
        if (v != null && !v.isNull()) {
            to.set(dst, v);
        }
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private String c2paJson(JsonNode c2pa) {
        if (c2pa == null || c2pa.isNull()) {
            return null;
        }
        String json = c2pa.toString();
        if (json.length() <= MAX_C2PA_CHARS) {
            return json;
        }
        ObjectNode slim = c2pa.deepCopy();
        slim.remove("ingredients");
        slim.remove("fmd_analysis");
        if (slim.has("actions") && slim.get("actions").size() > 10) {
            ArrayNode actions = mapper.createArrayNode();
            for (int i = 0; i < 10; i++) {
                actions.add(slim.get("actions").get(i));
            }
            slim.set("actions", actions);
        }
        slim.put("clipped", true);
        json = slim.toString();
        if (json.length() > MAX_C2PA_CHARS) {
            log.warn("C2PA summary too large ({} chars); not stored", json.length());
            return null;
        }
        return json;
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
        return updateDecision(id, decision, null);
    }

    /** Applies a moderator decision. Guards (breaks, appeals, second review, seniority) live in {@link ModerationService}. */
    @Transactional
    public AnalysisResult updateDecision(Long id, Decision decision, String actor) {
        AnalysisResult entity = repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found: " + id));
        Decision previous = entity.getDecision();
        boolean complaintWasOpen = entity.isComplianceOpen();
        entity.setDecision(decision);
        entity.setReviewedAt(clock.instant());
        entity.clearPending();
        if (actor != null) {
            entity.setDecidedBy(actor);
        }
        slaClock.stopClock(entity);
        AnalysisResult saved = repository.save(entity);
        provenance.decisionChanged(saved, previous, decision);
        if (decision == Decision.AUTO_FLAG && previous != Decision.AUTO_FLAG) {
            prescreen.confirmTakedown(saved.getPhash(), saved.getId());
        } else if (previous == Decision.AUTO_FLAG && decision != Decision.AUTO_FLAG) {
            prescreen.releaseTakedown(saved.getPhash(), saved.getId());
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
        stats.put("moderation", moderationAgreement());
        return stats;
    }

    /**
     * How moderators' final decisions compare with the model's original routing. Approve sends ALLOW
     * and reject sends AUTO_FLAG, so a REVIEW item ending as AUTO_FLAG was confirmed harmful.
     * {@code precisionProxy}: share of model-flagged items (REVIEW or AUTO_FLAG) a human confirmed.
     */
    Map<String, Object> moderationAgreement() {
        Map<Decision, Map<Decision, Long>> counts = new EnumMap<>(Decision.class);
        for (Object[] row : repository.countReviewedByModelAndHumanDecision()) {
            counts.computeIfAbsent((Decision) row[0], k -> new EnumMap<>(Decision.class))
                    .merge((Decision) row[1], ((Number) row[2]).longValue(), Long::sum);
        }
        long reviewConfirmed = count(counts, Decision.REVIEW, Decision.AUTO_FLAG);
        long reviewCleared = count(counts, Decision.REVIEW, Decision.ALLOW);
        long flagUpheld = count(counts, Decision.AUTO_FLAG, Decision.AUTO_FLAG);
        long flagOverturned = count(counts, Decision.AUTO_FLAG, Decision.ALLOW);
        long allowKept = count(counts, Decision.ALLOW, Decision.ALLOW);
        long allowTakenDown = count(counts, Decision.ALLOW, Decision.AUTO_FLAG);
        long reviewed = counts.values().stream().flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum();

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("confirmed", reviewConfirmed);
        review.put("cleared", reviewCleared);
        review.put("confirmRate", rate(reviewConfirmed, reviewConfirmed + reviewCleared));

        Map<String, Object> autoFlag = new LinkedHashMap<>();
        autoFlag.put("upheld", flagUpheld);
        autoFlag.put("overturned", flagOverturned);
        autoFlag.put("overturnRate", rate(flagOverturned, flagUpheld + flagOverturned));

        Map<String, Object> allow = new LinkedHashMap<>();
        allow.put("kept", allowKept);
        allow.put("takenDown", allowTakenDown);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewed", reviewed);
        out.put("review", review);
        out.put("autoFlag", autoFlag);
        out.put("allow", allow);
        out.put("precisionProxy", rate(
                reviewConfirmed + flagUpheld, reviewConfirmed + reviewCleared + flagUpheld + flagOverturned));
        return out;
    }

    private static long count(Map<Decision, Map<Decision, Long>> counts, Decision model, Decision human) {
        return counts.getOrDefault(model, Map.of()).getOrDefault(human, 0L);
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    /**
     * @param labelled copy of the upload with the provenance manifest embedded; null if labelling failed
     * @param embeddedSource where the upload was traced back to (manifest, pixel or pHash match); null if new
     */
    /**
     * @param notice the uploader's notice number + PIN (the PIN is only ever returned here)
     */
    public record AnalyzeOutcome(
            AnalysisResult result,
            String heatmap,
            ProvenanceService.LabelledImage labelled,
            Map<String, Object> embeddedSource,
            NoticeService.Notice notice) {

        public AnalyzeOutcome withResult(AnalysisResult updated) {
            return new AnalyzeOutcome(updated, heatmap, labelled, embeddedSource, notice);
        }
    }
}
