package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.model.ProvenanceEventType;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.AppealRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 23: uploader appeals against a takedown (EU DSA Art. 20 style). Filed with the upload notice
 * number + PIN; timed by its own SLA clock from the {@code (jurisdiction, APPEAL)} rule; decided by a
 * moderator other than the one who made the contested decision; a granted appeal restores the content
 * only after a second moderator confirms. Every step lands in the provenance trail and the report.
 */
@Service
public class AppealService {

    private static final int MAX_STATEMENT = 1000;
    static final String UPLOADER = "uploader";

    public enum Outcome { UPHOLD, GRANT }

    private final AppealRepository appeals;
    private final AnalysisResultRepository results;
    private final NoticeService notices;
    private final SlaClockService slaClock;
    private final ProvenanceService provenance;
    private final ModerationService moderation;
    private final ModerationAuditService audit;
    private final WellbeingService wellbeing;
    private final EscalationService escalation;
    private final DecisionThresholdProperties thresholds;
    private final Clock clock;

    public AppealService(
            AppealRepository appeals,
            AnalysisResultRepository results,
            NoticeService notices,
            SlaClockService slaClock,
            ProvenanceService provenance,
            ModerationService moderation,
            ModerationAuditService audit,
            WellbeingService wellbeing,
            EscalationService escalation,
            DecisionThresholdProperties thresholds,
            Clock clock) {
        this.escalation = escalation;
        this.thresholds = thresholds;
        this.appeals = appeals;
        this.results = results;
        this.notices = notices;
        this.slaClock = slaClock;
        this.provenance = provenance;
        this.moderation = moderation;
        this.audit = audit;
        this.wellbeing = wellbeing;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ uploader side

    /** @throws PinAttemptGuard.TooManyAttempts while the notice number is locked */
    @Transactional
    public Appeal file(String noticeNumber, String pin, String statement) {
        String text = statement == null ? "" : statement.trim();
        if (text.isEmpty()) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "statement_required", "Tell us why the decision is wrong.");
        }
        if (text.length() > MAX_STATEMENT) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "statement_too_long",
                    "The statement must be at most " + MAX_STATEMENT + " characters.");
        }
        AnalysisResult item = notices.verify(noticeNumber, pin).orElseThrow(AppealService::notFound);
        Optional<Appeal> existing = appeals.findByAnalysisId(item.getId());
        if (existing.isPresent()) {
            throw new ModerationException(HttpStatus.CONFLICT, "already_appealed",
                    "This decision has already been appealed.", Map.of("status", existing.get().getStatus().name()));
        }
        if (item.getDecision() != Decision.AUTO_FLAG) {
            throw new ModerationException(HttpStatus.CONFLICT, "not_appealable",
                    "Only content that was taken down can be appealed. This upload's decision is " + item.getDecision() + ".");
        }
        if (item.getPendingDecision() != null) {
            throw new ModerationException(HttpStatus.CONFLICT, "change_pending",
                    "Moderators are already reviewing a change to this decision.");
        }

        Instant now = clock.instant();
        SlaClockService.ResolvedRule rule = slaClock.rule(item.getJurisdiction(), ComplaintType.APPEAL);
        Appeal a = new Appeal();
        a.setCaseNumber(item.getNoticeNumber());
        a.setAnalysisId(item.getId());
        a.setStatement(text);
        a.setContestedDecision(item.getDecision());
        a.setContestedBy(item.getDecidedBy() != null ? item.getDecidedBy() : ProvenanceService.SYSTEM);
        a.setFiledAt(now);
        a.setSlaDeadline(now.plus(rule.window()));
        a.setSlaStatus(slaClock.statusAt(a.getSlaDeadline(), now));
        a.setSlaRuleLabel(rule.label());
        Appeal saved;
        try {
            saved = appeals.saveAndFlush(a);
        } catch (DataIntegrityViolationException ex) {
            throw new ModerationException(HttpStatus.CONFLICT, "already_appealed", "This decision has already been appealed.");
        }

        item.setAppealStatus(Appeal.Status.OPEN.name());
        results.save(item);
        provenance.record(item.getProvenanceId(), item.getId(), ProvenanceEventType.APPEAL_FILED, UPLOADER,
                "Uploader appealed the takedown; decision due within " + ProvenanceService.human(rule.window())
                        + " (" + rule.label() + ")", now);
        return saved;
    }

    public record Status(AnalysisResult item, Appeal appeal) {}

    /** @throws PinAttemptGuard.TooManyAttempts while the notice number is locked */
    @Transactional(readOnly = true)
    public Optional<Status> status(String noticeNumber, String pin) {
        return notices.verify(noticeNumber, pin)
                .map(item -> new Status(item, appeals.findByAnalysisId(item.getId()).orElse(null)));
    }

    /** What the uploader sees: no moderator names, no internal IDs, no scores. */
    public Map<String, Object> publicView(Status s) {
        AnalysisResult item = s.item();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("noticeNumber", item.getNoticeNumber());
        body.put("decision", item.getDecision().name());
        body.put("mediaType", item.isVideo() ? "VIDEO" : "IMAGE");
        body.put("analyzedAt", item.getCreatedAt().toString());
        body.put("appealable", s.appeal() == null && item.getDecision() == Decision.AUTO_FLAG && item.getPendingDecision() == null);
        body.put("changePending", item.getPendingDecision() != null);
        body.put("statementOfReasons", item.getDecision() == Decision.AUTO_FLAG ? statementOfReasons(item) : null);
        body.put("appeal", s.appeal() == null ? null : appealPublic(s.appeal()));
        return body;
    }

    /**
     * DSA Art. 17 style statement of reasons for a takedown: what kind of content it was judged to be,
     * on what basis, whether automated means detected and/or decided it, and the routes to redress.
     * Codes only; the frontend words them in the uploader's language.
     */
    Map<String, Object> statementOfReasons(AnalysisResult item) {
        List<String> grounds = new java.util.ArrayList<>();
        if (item.getForgeryScore() >= thresholds.getForgeryReview()) {
            grounds.add("MANIPULATED_MEDIA");
        }
        if (item.getExplicitScore() >= thresholds.getExplicitReview()) {
            grounds.add("EXPLICIT_CONTENT");
        }
        if (grounds.isEmpty()) {
            grounds.add("POLICY_VIOLATION");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grounds", grounds);
        m.put("basis", item.getComplaintType() != null ? "LEGAL_COMPLAINT" : "PLATFORM_POLICY");
        m.put("legalReference", item.getComplaintType() != null ? item.getSlaRuleLabel() : null);
        m.put("detectedAutomatically", item.getModelDecision() == null || item.getModelDecision() != Decision.ALLOW);
        m.put("decidedAutomatically", item.getDecidedBy() == null);
        m.put("redress", List.of("INTERNAL_APPEAL", "OUT_OF_COURT_SETTLEMENT", "JUDICIAL_REDRESS"));
        return m;
    }

    public static Map<String, Object> appealPublic(Appeal a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseNumber", a.getCaseNumber());
        m.put("status", a.getStatus().name());
        m.put("filedAt", a.getFiledAt().toString());
        m.put("decisionDueBy", a.getSlaDeadline().toString());
        m.put("rule", a.getSlaRuleLabel());
        m.put("decidedAt", str(a.getDecidedAt()));
        m.put("reason", a.getStatus() == Appeal.Status.OPEN ? null : a.getOutcomeReason());
        m.put("reinstated", a.getReinstatedAt() != null);
        m.put("reinstatementPending", a.getStatus() == Appeal.Status.GRANTED && a.getReinstatedAt() == null);
        return m;
    }

    // ------------------------------------------------------------------ moderator side

    /** {@code status=open} (default): by nearest deadline. {@code status=decided}: most recent 50. */
    @Transactional(readOnly = true)
    public List<Appeal> list(String status) {
        return "decided".equalsIgnoreCase(status)
                ? appeals.findTop50ByStatusNotOrderByDecidedAtDesc(Appeal.Status.OPEN)
                : appeals.findByStatusOrderBySlaDeadlineAsc(Appeal.Status.OPEN);
    }

    @Transactional(readOnly = true)
    public Optional<Appeal> forItem(Long analysisId) {
        return appeals.findByAnalysisId(analysisId);
    }

    @Transactional
    public Appeal decide(Long appealId, String rawOutcome, String reason, String actor) {
        wellbeing.requireNotOnBreak(actor);
        Appeal a = appeals.findById(appealId)
                .orElseThrow(() -> new ModerationException(HttpStatus.NOT_FOUND, "not_found", "Appeal not found."));
        if (!a.isOpen()) {
            throw new ModerationException(HttpStatus.CONFLICT, "appeal_closed", "This appeal has already been decided.");
        }
        if (moderation.appealConflicts(a).contains(actor)) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "conflict_of_interest",
                    "An appeal must be decided by a different moderator than the one who made or upheld the contested decision.");
        }
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(String.valueOf(rawOutcome).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_outcome", "outcome must be UPHOLD or GRANT.");
        }
        if (reason == null || reason.isBlank()) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "reason_required",
                    "Give the uploader a statement of reasons for the outcome.");
        }
        AnalysisResult item = results.findById(a.getAnalysisId())
                .orElseThrow(() -> new ModerationException(HttpStatus.NOT_FOUND, "not_found", "Appealed item not found."));

        Instant now = clock.instant();
        a.setStatus(outcome == Outcome.GRANT ? Appeal.Status.GRANTED : Appeal.Status.UPHELD);
        a.setDecidedAt(now);
        a.setDecidedBy(actor);
        a.setOutcomeReason(reason.trim().length() > MAX_STATEMENT ? reason.trim().substring(0, MAX_STATEMENT) : reason.trim());
        a.setSlaStatus(slaClock.statusAt(a.getSlaDeadline(), now));
        Appeal saved = appeals.save(a);

        item.setAppealStatus(saved.getStatus().name());
        item.setAppealDecidedAt(now);
        results.save(item);

        Duration left = Duration.between(now, a.getSlaDeadline());
        String timing = left.isNegative()
                ? ProvenanceService.human(left) + " after the deadline (appeal SLA breached)"
                : ProvenanceService.human(left) + " before the deadline";
        provenance.record(item.getProvenanceId(), item.getId(), ProvenanceEventType.APPEAL_DECIDED, ProvenanceService.MODERATOR,
                "Appeal " + a.getCaseNumber() + " decided " + timing + ": "
                        + (outcome == Outcome.GRANT ? "granted; reinstatement awaiting second review" : "takedown upheld"), now);
        audit.record(actor, ModerationAction.APPEAL_DECIDED, item.getId(), saved.getId(),
                outcome + " (contested decision by " + a.getContestedBy() + "): " + saved.getOutcomeReason());
        if (outcome == Outcome.GRANT) {
            moderation.proposeOverride(item, Decision.ALLOW, actor, "Appeal " + a.getCaseNumber() + " granted", saved.getId());
        }
        wellbeing.recordExposure(actor, item.getId());
        return saved;
    }

    public Map<String, Object> moderatorView(Appeal a, AnalysisResult item, String viewer) {
        return moderatorView(a, item, viewer, moderation.appealConflicts(a));
    }

    static Map<String, Object> moderatorView(Appeal a, AnalysisResult item, String viewer, java.util.Set<String> conflicts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("caseNumber", a.getCaseNumber());
        m.put("analysisId", a.getAnalysisId());
        m.put("statement", a.getStatement());
        m.put("status", a.getStatus().name());
        m.put("contestedDecision", a.getContestedDecision().name());
        m.put("contestedBy", a.getContestedBy());
        m.put("filedAt", a.getFiledAt().toString());
        m.put("slaDeadline", a.getSlaDeadline().toString());
        m.put("slaStatus", a.getSlaStatus().name());
        m.put("slaBadge", SlaClockService.badge(a.getSlaRuleLabel(), Duration.between(a.getFiledAt(), a.getSlaDeadline())));
        m.put("escalatedAt", str(a.getEscalatedAt()));
        m.put("decidedAt", str(a.getDecidedAt()));
        m.put("decidedBy", a.getDecidedBy());
        m.put("outcomeReason", a.getOutcomeReason());
        m.put("reinstatedAt", str(a.getReinstatedAt()));
        m.put("conflicted", List.copyOf(conflicts));
        m.put("canDecide", a.isOpen() && viewer != null && !conflicts.contains(viewer));
        if (item != null) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("decision", item.getDecision().name());
            i.put("pendingDecision", item.getPendingDecision() != null ? item.getPendingDecision().name() : null);
            i.put("forgeryScore", item.getForgeryScore());
            i.put("explicitScore", item.getExplicitScore());
            i.put("mediaType", item.isVideo() ? "VIDEO" : "IMAGE");
            i.put("jurisdiction", item.getJurisdiction());
            i.put("provenanceId", item.getProvenanceId());
            i.put("phash", item.getPhash());
            m.put("item", i);
        }
        return m;
    }

    // ------------------------------------------------------------------ SLA clock

    /**
     * Moves open appeals between ON_TRACK / AT_RISK / BREACHED and escalates each transition through
     * {@link EscalationService} (log, metric, webhook), like a complaint clock. Run by {@link SlaScheduler}
     * under its cluster-wide lock, so a transition escalates once, not once per instance.
     */
    @Transactional
    public int recalculate() {
        Instant now = clock.instant();
        int changed = 0;
        for (Appeal a : appeals.findByStatusOrderBySlaDeadlineAsc(Appeal.Status.OPEN)) {
            SlaStatus next = slaClock.statusAt(a.getSlaDeadline(), now);
            if (next == a.getSlaStatus()) {
                continue;
            }
            a.setSlaStatus(next);
            if (next != SlaStatus.ON_TRACK) {
                AnalysisResult item = results.findById(a.getAnalysisId()).orElse(null);
                escalation.escalateAppeal(a, item, next);
            }
            appeals.save(a);
            changed++;
        }
        return changed;
    }

    /** Aggregate view of open appeal clocks for the compliance dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Object> slaSummary() {
        List<Appeal> open = appeals.findByStatusOrderBySlaDeadlineAsc(Appeal.Status.OPEN);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", open.size());
        for (SlaStatus s : SlaStatus.values()) {
            m.put(s.name().toLowerCase(Locale.ROOT), open.stream().filter(a -> a.getSlaStatus() == s).count());
        }
        m.put("nextDeadline", open.isEmpty() ? null : open.get(0).getSlaDeadline().toString());
        return m;
    }

    private static ModerationException notFound() {
        return new ModerationException(HttpStatus.NOT_FOUND, "not_found",
                "We couldn't find an upload with that notice number and PIN.");
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }
}
