package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.ProvenanceEvent;
import com.forgeddetect.model.ProvenanceEventType;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ProvenanceEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 14: traceable provenance IDs, signed manifests embedded in labelled media (via the ML
 * service), re-upload lineage, and a public audit trail per ID.
 *
 * <p>The audit trail is public, so it never includes complaint references or moderator names.
 */
@Service
public class ProvenanceService {

    public enum MatchVia {
        /** The upload carried a manifest issued by this system. */
        MANIFEST,
        /** No manifest, but pixel-identical to an earlier analysis (metadata was stripped). */
        CONTENT_HASH,
        /** No manifest, but the perceptual hash equals an earlier analysis (visually the same). */
        PHASH
    }

    /** An earlier analysis an upload was traced to. {@code source} is null if a manifest's ID is unknown here. */
    public record SourceMatch(MatchVia via, AnalysisResult source, MlProvenanceCheck manifest) {}

    public record LabelledImage(String base64, String mimeType) {}

    static final String SYSTEM = "system";
    static final String MODERATOR = "moderator";

    private static final Logger log = LoggerFactory.getLogger(ProvenanceService.class);

    private final MlClientService mlClient;
    private final AnalysisResultRepository results;
    private final ProvenanceEventRepository events;
    private final Clock clock;

    public ProvenanceService(
            MlClientService mlClient,
            AnalysisResultRepository results,
            ProvenanceEventRepository events,
            Clock clock) {
        this.mlClient = mlClient;
        this.results = results;
        this.events = events;
        this.clock = clock;
    }

    public String newId() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ lineage + labelling

    /** Call before saving the new analysis so it can't match itself. */
    @Transactional(readOnly = true)
    public SourceMatch trace(MlProvenanceCheck manifest, String contentHash, String phash) {
        if (manifest != null && notBlank(manifest.getProvenanceId())) {
            return new SourceMatch(
                    MatchVia.MANIFEST, results.findByProvenanceId(manifest.getProvenanceId()).orElse(null), manifest);
        }
        if (notBlank(contentHash)) {
            Optional<AnalysisResult> hit =
                    results.findFirstByContentHashAndProvenanceIdIsNotNullOrderByCreatedAtAsc(contentHash);
            if (hit.isPresent()) {
                return new SourceMatch(MatchVia.CONTENT_HASH, hit.get(), null);
            }
        }
        if (notBlank(phash)) {
            Optional<AnalysisResult> hit = results.findFirstByPhashAndProvenanceIdIsNotNullOrderByCreatedAtAsc(phash);
            if (hit.isPresent()) {
                return new SourceMatch(MatchVia.PHASH, hit.get(), null);
            }
        }
        return null;
    }

    /** Best effort: analysis still succeeds (without a labelled copy) if the ML service can't label. */
    public LabelledImage label(AnalysisResult item, byte[] imageBytes, String filename, String contentType) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("provenance_id", item.getProvenanceId());
        fields.put("analyzed_at", item.getCreatedAt().toString());
        fields.put("forgery_score", String.valueOf(clamp(item.getForgeryScore())));
        fields.put("explicit_score", String.valueOf(clamp(item.getExplicitScore())));
        fields.put("decision", item.getDecision().name());
        if (item.getSourceProvenanceId() != null) {
            fields.put("derived_from", item.getSourceProvenanceId());
        }
        try {
            MlLabelResponse labelled = mlClient.label(imageBytes, filename, contentType, fields);
            if (labelled == null || labelled.image() == null) {
                return null;
            }
            return new LabelledImage(labelled.image(), labelled.mimeType());
        } catch (Exception ex) {
            log.warn("Could not label analysis #{} ({}): {}", item.getId(), item.getProvenanceId(), ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ audit events

    public void recordAnalysis(AnalysisResult item, SourceMatch match) {
        record(item.getProvenanceId(), item.getId(), ProvenanceEventType.ANALYZED, SYSTEM,
                String.format(Locale.ROOT, "Scored forgery %.2f, explicit %.2f; routed to %s",
                        item.getForgeryScore(), item.getExplicitScore(), item.getDecision()),
                item.getCreatedAt());
        if (match == null || match.source() == null) {
            return;
        }
        record(match.source().getProvenanceId(), item.getId(), ProvenanceEventType.REUPLOAD_DETECTED, SYSTEM,
                "Seen again as analysis #" + item.getId() + " " + describe(match), item.getCreatedAt());
    }

    public void decisionChanged(AnalysisResult item, Decision from, Decision to) {
        String detail = from == to ? "Decision confirmed as " + to : "Decision changed from " + from + " to " + to;
        record(item.getProvenanceId(), item.getId(), ProvenanceEventType.DECISION_CHANGED, MODERATOR, detail, clock.instant());
    }

    public void complaintOpened(AnalysisResult item, ComplaintType type, Duration window, Instant receivedAt) {
        String who = type == ComplaintType.GOVERNMENT_ORDER ? "Government takedown order" : "User deepfake / NCII complaint";
        record(item.getProvenanceId(), item.getId(), ProvenanceEventType.COMPLAINT_OPENED,
                type == ComplaintType.GOVERNMENT_ORDER ? "government" : "complainant",
                who + " received; " + human(window) + " takedown deadline", receivedAt);
    }

    public void escalated(AnalysisResult item, SlaStatus status, Duration remaining) {
        String detail = status == SlaStatus.BREACHED
                ? "SLA breached, " + human(remaining) + " overdue; on-call escalated"
                : "SLA at risk, " + human(remaining) + " left; on-call escalated";
        record(item.getProvenanceId(), item.getId(), ProvenanceEventType.SLA_ESCALATED, SYSTEM, detail, clock.instant());
    }

    public void slaResolved(AnalysisResult item, Duration remaining) {
        String detail = remaining.isNegative()
                ? "Complaint resolved " + human(remaining) + " after the deadline (SLA breached)"
                : "Complaint resolved " + human(remaining) + " before the deadline";
        record(item.getProvenanceId(), item.getId(), ProvenanceEventType.SLA_RESOLVED, MODERATOR, detail, clock.instant());
    }

    public void record(String provenanceId, Long analysisId, ProvenanceEventType type, String actor, String detail, Instant at) {
        if (provenanceId == null) {
            return;
        }
        String text = detail != null && detail.length() > 512 ? detail.substring(0, 512) : detail;
        events.save(new ProvenanceEvent(provenanceId, analysisId, type, at, actor, text));
    }

    /** Gives rows created before Phase 14 an ID so every analysis is verifiable. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillMissingIds() {
        List<AnalysisResult> legacy = results.findByProvenanceIdIsNull();
        for (AnalysisResult item : legacy) {
            item.setProvenanceId(newId());
            results.save(item);
            record(item.getProvenanceId(), item.getId(), ProvenanceEventType.ANALYZED, SYSTEM,
                    "Provenance ID assigned retroactively; routed to " + item.getDecision(), clock.instant());
        }
        if (!legacy.isEmpty()) {
            log.info("Assigned provenance IDs to {} pre-existing analyses", legacy.size());
        }
    }

    // ------------------------------------------------------------------ verification views

    /** Public audit trail for a provenance ID; empty if unknown. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> trail(String provenanceId) {
        return results.findByProvenanceId(provenanceId).map(this::trailView);
    }

    /** Reads a manifest from uploaded media (no new analysis is stored) and resolves its trail. */
    @Transactional(readOnly = true)
    public Map<String, Object> verifyUpload(byte[] imageBytes, String filename, String contentType) {
        MlReadResponse read = mlClient.readProvenance(imageBytes, filename, contentType);
        if (read == null) {
            throw new IllegalStateException("ML service returned empty response");
        }
        SourceMatch match = trace(read.provenance(), read.contentHash(), read.phash());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("match", match != null ? match.via().name() : "NONE");
        body.put("manifest", manifestView(read.provenance()));
        body.put("record", match != null && match.source() != null ? trailView(match.source()) : null);
        body.put("contentHash", read.contentHash());
        body.put("phash", read.phash());
        return body;
    }

    /** What the analyze response says about where an upload came from; null if nothing matched. */
    public Map<String, Object> matchView(SourceMatch match) {
        if (match == null) {
            return null;
        }
        AnalysisResult source = match.source();
        MlProvenanceCheck manifest = match.manifest();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("via", match.via().name());
        body.put("known", source != null);
        body.put("provenanceId", source != null ? source.getProvenanceId() : manifest.getProvenanceId());
        body.put("analysisId", source != null ? source.getId() : null);
        body.put("analyzedAt", source != null ? source.getCreatedAt().toString() : manifest.getAnalyzedAt());
        body.put("decision", source != null ? source.getDecision().name() : manifest.getDecision());
        body.put("manifest", manifestView(manifest));
        return body;
    }

    public Map<String, Object> manifestView(MlProvenanceCheck m) {
        if (m == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", m.getStatus());
        body.put("signatureValid", m.isSignatureValid());
        body.put("contentMatch", m.isContentMatch());
        body.put("provenanceId", m.getProvenanceId());
        body.put("issuer", m.getIssuer());
        body.put("spec", m.getSpec());
        body.put("analyzedAt", m.getAnalyzedAt());
        body.put("forgeryScore", m.getForgeryScore());
        body.put("explicitScore", m.getExplicitScore());
        body.put("decision", m.getDecision());
        body.put("derivedFrom", blankToNull(m.getDerivedFrom()));
        body.put("contentHash", m.getContentHash());
        return body;
    }

    private Map<String, Object> trailView(AnalysisResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provenanceId", r.getProvenanceId());
        body.put("analysisId", r.getId());
        body.put("analyzedAt", r.getCreatedAt().toString());
        body.put("decision", r.getDecision().name());
        body.put("forgeryScore", r.getForgeryScore());
        body.put("explicitScore", r.getExplicitScore());
        body.put("phash", r.getPhash());
        body.put("contentHash", r.getContentHash());
        body.put("derivedFrom", r.getSourceProvenanceId());
        body.put("reuploads", results.countBySourceProvenanceId(r.getProvenanceId()));
        if (r.getComplaintType() != null) {
            Map<String, Object> complaint = new LinkedHashMap<>();
            complaint.put("type", r.getComplaintType().name());
            complaint.put("slaStatus", r.getSlaStatus() != null ? r.getSlaStatus().name() : null);
            complaint.put("receivedAt", str(r.getComplaintReceivedAt()));
            complaint.put("deadline", str(r.getSlaDeadline()));
            complaint.put("resolvedAt", str(r.getResolvedAt()));
            body.put("complaint", complaint);
        } else {
            body.put("complaint", null);
        }
        body.put("events", events.findByProvenanceIdOrderByOccurredAtAscIdAsc(r.getProvenanceId()).stream()
                .map(e -> {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("type", e.getType().name());
                    ev.put("occurredAt", e.getOccurredAt().toString());
                    ev.put("actor", e.getActor());
                    ev.put("analysisId", e.getAnalysisId());
                    ev.put("detail", e.getDetail());
                    return ev;
                })
                .toList());
        return body;
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(SourceMatch match) {
        return switch (match.via()) {
            case MANIFEST -> "via its embedded manifest (" + switch (String.valueOf(match.manifest().getStatus())) {
                case "VERIFIED" -> "signature valid, pixels unchanged";
                case "CONTENT_MODIFIED" -> "signature valid, pixels edited since labelling";
                default -> "manifest signature invalid";
            } + ")";
            case CONTENT_HASH -> "via identical pixels (metadata stripped)";
            case PHASH -> "via perceptual-hash match (visually similar)";
        };
    }

    static String human(Duration d) {
        long total = Math.abs(d.toSeconds());
        long h = total / 3600;
        long m = (total % 3600) / 60;
        if (h > 0) {
            return m > 0 ? h + "h " + m + "m" : h + "h";
        }
        return m > 0 ? m + "m" : total + "s";
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }
}
