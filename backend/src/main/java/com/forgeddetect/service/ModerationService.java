package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.model.ModeratorAccount;
import com.forgeddetect.model.ModeratorRole;
import com.forgeddetect.model.ProvenanceEventType;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.AppealRepository;
import com.forgeddetect.repository.ModeratorAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 23: the rules around a moderator decision.
 * <ul>
 *   <li>No decisions or reveals during an enforced wellbeing break (423).</li>
 *   <li>An item under appeal is decided through the appeal, not the queue (409).</li>
 *   <li>An item escalated to a senior (review or takedown) can only be decided, and second-reviewed,
 *       by a SENIOR_MODERATOR (403).</li>
 *   <li>An item assigned to someone else, or routed to another team, can't be decided by a regular moderator (409).</li>
 *   <li>Moving an item away from AUTO_FLAG is only a proposal until a different moderator confirms it.</li>
 * </ul>
 */
@Service
public class ModerationService {

    public record Moderator(String username, String displayName, ModeratorRole role, String team) {

        public boolean admin() {
            return role == ModeratorRole.ADMIN;
        }

        /** Seniors and the owner admin may decide escalated work and approve legal requests. */
        public boolean senior() {
            return role == ModeratorRole.SENIOR_MODERATOR || role == ModeratorRole.ADMIN;
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", username);
            m.put("displayName", displayName);
            m.put("role", role.name());
            m.put("team", team);
            m.put("senior", senior());
            m.put("admin", admin());
            return m;
        }
    }

    /** {@code pendingSecondReview}: the decision was recorded as a proposal, not applied. */
    public record Outcome(AnalysisResult item, boolean pendingSecondReview) {}

    private final AnalysisResultRepository results;
    private final AnalysisService analysis;
    private final AppealRepository appeals;
    private final ModeratorAccountRepository accounts;
    private final ProvenanceService provenance;
    private final ModerationAuditService audit;
    private final WellbeingService wellbeing;
    private final Clock clock;

    public ModerationService(
            AnalysisResultRepository results,
            AnalysisService analysis,
            AppealRepository appeals,
            ModeratorAccountRepository accounts,
            ProvenanceService provenance,
            ModerationAuditService audit,
            WellbeingService wellbeing,
            Clock clock) {
        this.results = results;
        this.analysis = analysis;
        this.appeals = appeals;
        this.accounts = accounts;
        this.provenance = provenance;
        this.audit = audit;
        this.wellbeing = wellbeing;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ who

    /** Accounts not in the table (e.g. test principals) are treated as regular moderators without a team. */
    @Transactional(readOnly = true)
    public Moderator moderator(String username) {
        return accounts.findByUsername(username)
                .map(ModerationService::toModerator)
                .orElse(new Moderator(username, username, ModeratorRole.MODERATOR, null));
    }

    @Transactional(readOnly = true)
    public Map<String, List<Map<String, Object>>> teams() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (ModeratorAccount a : staff()) {
            out.computeIfAbsent(a.getTeam(), k -> new java.util.ArrayList<>()).add(toModerator(a).view());
        }
        return out;
    }

    /** Moderator accounts only: investigators log in but are never assigned work or counted in a team. */
    private List<ModeratorAccount> staff() {
        return accounts.findAllByOrderByTeamAscUsernameAsc().stream().filter(a -> !a.isInvestigator()).toList();
    }

    private static Moderator toModerator(ModeratorAccount a) {
        return new Moderator(a.getUsername(), a.getDisplayName(), a.getRole(), a.getTeam());
    }

    // ------------------------------------------------------------------ queues

    /**
     * @param status review | flagged | second-review | escalated
     * @param scope  all | mine | team | unassigned
     */
    @Transactional(readOnly = true)
    public List<AnalysisResult> queue(String status, String scope, String actor) {
        String s = status == null ? "review" : status.trim().toLowerCase(Locale.ROOT);
        List<AnalysisResult> items = switch (s) {
            case "flagged" -> analysis.flagged().stream().filter(r -> !r.isSeniorEscalated()).toList();
            case "second-review" -> results.findByPendingDecisionIsNotNullOrderByPendingProposedAtAsc();
            case "escalated" -> results
                    .findBySeniorEscalatedAtIsNotNullAndSeniorResolvedAtIsNullAndPendingDecisionIsNullOrderBySeniorEscalatedAtAsc();
            default -> analysis.queue().stream()
                    .filter(r -> !r.isSeniorEscalated() && r.getPendingDecision() == null).toList();
        };
        return items.stream().filter(scopeFilter(scope, moderator(actor))).toList();
    }

    /** {@code team}: items routed to the moderator's team, whether or not a team member has claimed them. */
    private static Predicate<AnalysisResult> scopeFilter(String scope, Moderator me) {
        String s = scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "mine" -> r -> me.username().equals(r.getAssignedTo());
            case "team" -> r -> me.team() != null
                    && (me.team().equals(r.getAssignedTeam()) || me.team().equals(r.getRoutedTeam()));
            case "unassigned" -> r -> r.getAssignedTo() == null && r.getRoutedTeam() == null;
            default -> r -> true;
        };
    }

    /**
     * Who may not decide an appeal: the moderator recorded as owning the contested decision, plus
     * anyone who set or upheld the takedown before the appeal was filed.
     */
    @Transactional(readOnly = true)
    public Set<String> appealConflicts(Appeal a) {
        Set<String> out = new TreeSet<>(audit.takedownOwners(a.getAnalysisId(), a.getFiledAt()));
        if (a.getContestedBy() != null && !ProvenanceService.SYSTEM.equals(a.getContestedBy())) {
            out.add(a.getContestedBy());
        }
        return out;
    }

    // ------------------------------------------------------------------ decisions

    @Transactional
    public Outcome decide(Long id, Decision decision, String actor, String reason) {
        wellbeing.requireNotOnBreak(actor);
        AnalysisResult item = find(id);
        Moderator me = moderator(actor);
        Optional<Appeal> appeal = appeals.findByAnalysisId(id);
        if (appeal.isPresent() && appeal.get().isOpen()) {
            throw new ModerationException(HttpStatus.CONFLICT, "appeal_open",
                    "This item has an open appeal (" + appeal.get().getCaseNumber() + "). Decide it from the appeals queue.");
        }
        if (item.getPendingDecision() != null) {
            throw new ModerationException(HttpStatus.CONFLICT, "second_review_pending",
                    "A change to this item is waiting for second review.");
        }
        requireCanAct(item, me);

        if (item.getDecision() == Decision.AUTO_FLAG && decision != Decision.AUTO_FLAG) {
            // An escalated takedown stays in the senior's hands until the second review completes
            proposeOverride(item, decision, actor, reason, null);
            wellbeing.recordExposure(actor, id);
            return new Outcome(item, true);
        }
        audit.record(actor, ModerationAction.DECIDED, id, null, ModerationAuditService.decidedDetail(decision, reason));
        resolveEscalation(id, actor);
        AnalysisResult saved = analysis.updateDecision(id, decision, actor);
        wellbeing.recordExposure(actor, id);
        return new Outcome(saved, false);
    }

    /** Marks an open senior escalation as handled. Called only after {@link #requireCanAct} has checked seniority. */
    private void resolveEscalation(Long id, String actor) {
        AnalysisResult item = find(id);
        if (!item.isSeniorEscalated()) {
            return;
        }
        item.setSeniorResolvedAt(clock.instant());
        item.setSeniorResolvedBy(actor);
        results.save(item);
    }

    /**
     * Records a proposed move away from AUTO_FLAG; the takedown stays in place until a different
     * moderator confirms it. Also used when an appeal is granted.
     */
    @Transactional
    public void proposeOverride(AnalysisResult item, Decision to, String actor, String reason, Long appealId) {
        Instant now = clock.instant();
        item.setPendingDecision(to);
        item.setPendingProposedBy(actor);
        item.setPendingProposedAt(now);
        item.setPendingReason(trim(reason, 500));
        results.save(item);
        provenance.record(item.getProvenanceId(), item.getId(), ProvenanceEventType.SECOND_REVIEW_REQUESTED,
                ProvenanceService.MODERATOR,
                "Change from " + item.getDecision() + " to " + to + " proposed; awaiting second review", now);
        audit.record(actor, ModerationAction.OVERRIDE_PROPOSED, item.getId(), appealId,
                "Proposed " + item.getDecision() + " -> " + to + (reason != null && !reason.isBlank() ? ": " + reason.trim() : ""));
    }

    /**
     * Confirms or rejects a pending override. Confirming requires a moderator other than the proposer.
     * For a granted appeal, neither the granting moderator nor anyone who owned the takedown may confirm
     * <em>or</em> reject it, and rejecting needs a statement of reasons for the uploader.
     * On an escalated item the second reviewer must also be a senior.
     */
    @Transactional
    public AnalysisResult secondReview(Long id, boolean confirm, String note, String actor) {
        wellbeing.requireNotOnBreak(actor);
        AnalysisResult item = find(id);
        Decision proposed = item.getPendingDecision();
        if (proposed == null) {
            throw new ModerationException(HttpStatus.CONFLICT, "no_pending_review", "Nothing is waiting for second review on this item.");
        }
        String proposer = item.getPendingProposedBy();
        Optional<Appeal> grant = pendingGrant(id);
        ModerationException blocked = secondReviewBlock(item, grant, moderator(actor), confirm);
        if (blocked != null) {
            throw blocked;
        }
        if (grant.isPresent() && !confirm && (note == null || note.isBlank())) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "reason_required",
                    "Rejecting a granted appeal changes its outcome: give the uploader a statement of reasons.");
        }
        Instant now = clock.instant();
        Long appealId = grant.map(Appeal::getId).orElse(null);
        String suffix = note != null && !note.isBlank() ? ": " + note.trim() : "";
        resolveEscalation(id, actor);

        if (confirm) {
            AnalysisResult saved = analysis.updateDecision(id, proposed, proposer);
            provenance.record(saved.getProvenanceId(), id, ProvenanceEventType.SECOND_REVIEW_COMPLETED, ProvenanceService.MODERATOR,
                    "Second review confirmed the change to " + proposed, now);
            audit.record(actor, ModerationAction.SECOND_REVIEW_CONFIRMED, id, appealId,
                    "Confirmed " + proposed + " proposed by " + proposer + suffix);
            grant.ifPresent(a -> {
                a.setReinstatedAt(now);
                appeals.save(a);
                provenance.record(saved.getProvenanceId(), id, ProvenanceEventType.APPEAL_DECIDED, ProvenanceService.MODERATOR,
                        "Appeal " + a.getCaseNumber() + ": content reinstated after second review", now);
            });
            wellbeing.recordExposure(actor, id);
            return saved;
        }

        item.clearPending();
        // The reviewer who keeps the takedown in place now owns it (and can't decide a later appeal)
        item.setDecidedBy(actor);
        item.setReviewedAt(now);
        grant.ifPresent(a -> {
            audit.record(actor, ModerationAction.APPEAL_DECIDED, id, a.getId(),
                    "UPHOLD at second review, overturning " + a.getDecidedBy() + "'s grant (" + a.getOutcomeReason() + ")" + suffix);
            a.setStatus(Appeal.Status.UPHELD);
            a.setDecidedBy(actor);
            a.setDecidedAt(now);
            a.setOutcomeReason(trim(note, 1000));
            appeals.save(a);
            item.setAppealStatus(Appeal.Status.UPHELD.name());
            item.setAppealDecidedAt(now);
            provenance.record(item.getProvenanceId(), id, ProvenanceEventType.APPEAL_DECIDED, ProvenanceService.MODERATOR,
                    "Appeal " + a.getCaseNumber() + ": takedown upheld after second review", now);
        });
        AnalysisResult saved = results.save(item);
        provenance.record(saved.getProvenanceId(), id, ProvenanceEventType.SECOND_REVIEW_COMPLETED, ProvenanceService.MODERATOR,
                "Second review rejected the change to " + proposed + "; decision stays " + saved.getDecision(), now);
        audit.record(actor, ModerationAction.SECOND_REVIEW_REJECTED, id, appealId,
                "Rejected " + proposed + " proposed by " + proposer + suffix);
        return saved;
    }

    /** Whether {@code viewer} may confirm the pending change on {@code item} (same rules as {@link #secondReview}). */
    @Transactional(readOnly = true)
    public boolean canSecondReview(AnalysisResult item, String viewer) {
        return item.getPendingDecision() != null && viewer != null
                && secondReviewBlock(item, pendingGrant(item.getId()), moderator(viewer), true) == null;
    }

    private Optional<Appeal> pendingGrant(Long analysisId) {
        return appeals.findByAnalysisId(analysisId)
                .filter(a -> a.getStatus() == Appeal.Status.GRANTED && a.getReinstatedAt() == null);
    }

    private ModerationException secondReviewBlock(AnalysisResult item, Optional<Appeal> grant, Moderator me, boolean confirm) {
        String actor = me.username();
        if (item.isSeniorEscalated() && !me.senior()) {
            return new ModerationException(HttpStatus.FORBIDDEN, "senior_required",
                    "This item is escalated to a senior moderator, so its second review needs a senior too.");
        }
        // Owner admin may solo-operate four-eyes checks; the audit log still names them.
        if (me.admin()) {
            return null;
        }
        if (confirm && actor.equals(item.getPendingProposedBy())) {
            return new ModerationException(HttpStatus.FORBIDDEN, "conflict_of_interest",
                    "A second review must be done by a different moderator than the one who proposed the change.");
        }
        if (grant.isPresent()) {
            if (actor.equals(item.getPendingProposedBy()) || actor.equals(grant.get().getDecidedBy())) {
                return new ModerationException(HttpStatus.FORBIDDEN, "conflict_of_interest",
                        "The moderator who granted the appeal can't also do its second review.");
            }
            if (appealConflicts(grant.get()).contains(actor)) {
                return new ModerationException(HttpStatus.FORBIDDEN, "conflict_of_interest",
                        "The moderator whose decision was appealed can't review the appeal outcome.");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ teams & escalation

    /**
     * Regular moderators can only take items themselves (and only unrouted items or ones routed to their
     * own team); seniors can assign anyone, or route an item to a whole team with {@code team}.
     */
    @Transactional
    public AnalysisResult assign(Long id, String assignee, String team, String actor) {
        AnalysisResult item = find(id);
        Moderator me = moderator(actor);
        if (item.isSeniorEscalated() && !me.senior()) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "senior_required", "This item is escalated to a senior moderator.");
        }
        if ((assignee == null || assignee.isBlank()) && team != null && !team.isBlank()) {
            return routeToTeam(item, team.trim(), me);
        }
        String target = assignee == null || assignee.isBlank() ? actor : assignee.trim();
        if (!target.equals(actor) && !me.senior()) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "senior_required", "Only a senior moderator can assign items to others.");
        }
        if (item.getAssignedTo() != null && !item.getAssignedTo().equals(target) && !me.senior()) {
            throw new ModerationException(HttpStatus.CONFLICT, "assigned_elsewhere", "This item is already assigned to " + item.getAssignedTo() + ".");
        }
        requireTeamAccess(item, me);
        ModeratorAccount account = accounts.findByUsername(target).filter(a -> !a.isInvestigator()).orElse(null);
        if (account == null && !target.equals(actor)) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "unknown_moderator", "No moderator named " + target + ".");
        }
        item.setAssignedTo(target);
        item.setAssignedTeam(account != null ? account.getTeam() : me.team());
        item.setAssignedAt(clock.instant());
        audit.record(me.username(), ModerationAction.ASSIGNED, id, null,
                "Assigned to " + target + (item.getAssignedTeam() != null ? " (team " + item.getAssignedTeam() + ")" : ""));
        return results.save(item);
    }

    private AnalysisResult routeToTeam(AnalysisResult item, String team, Moderator me) {
        if (!me.senior()) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "senior_required", "Only a senior moderator can route items to a team.");
        }
        if (staff().stream().noneMatch(a -> team.equals(a.getTeam()))) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "unknown_team", "No team named " + team + ".");
        }
        item.setAssignedTo(null);
        item.setAssignedTeam(team);
        item.setRoutedTeam(team);
        item.setAssignedAt(clock.instant());
        audit.record(me.username(), ModerationAction.ASSIGNED, item.getId(), null, "Routed to team " + team + " (unclaimed)");
        return results.save(item);
    }

    /** Releases the person; an item routed to a team goes back to that team's pool. */
    @Transactional
    public AnalysisResult unassign(Long id, String actor) {
        AnalysisResult item = find(id);
        if (item.getAssignedTo() == null) {
            return item;
        }
        if (!item.getAssignedTo().equals(actor) && !moderator(actor).senior()) {
            throw new ModerationException(HttpStatus.CONFLICT, "assigned_elsewhere", "Only the assignee or a senior moderator can release this item.");
        }
        audit.record(actor, ModerationAction.UNASSIGNED, id, null, "Released by " + actor + " (was " + item.getAssignedTo() + ")"
                + (item.getRoutedTeam() != null ? "; back in team " + item.getRoutedTeam() + "'s pool" : ""));
        item.setAssignedTo(null);
        item.setAssignedTeam(item.getRoutedTeam());
        item.setAssignedAt(null);
        return results.save(item);
    }

    /** Hands the item to the senior queue; only SENIOR_MODERATOR can decide it from then on. */
    @Transactional
    public AnalysisResult escalate(Long id, String reason, String actor) {
        AnalysisResult item = find(id);
        if (item.isSeniorEscalated()) {
            throw new ModerationException(HttpStatus.CONFLICT, "already_escalated", "This item is already escalated.");
        }
        if (reason == null || reason.isBlank()) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "reason_required", "Say why this needs a senior moderator.");
        }
        Instant now = clock.instant();
        item.setSeniorEscalatedAt(now);
        item.setSeniorEscalatedBy(actor);
        item.setSeniorEscalationReason(trim(reason, 500));
        item.setSeniorResolvedAt(null);
        item.setSeniorResolvedBy(null);
        item.setAssignedTo(null);
        item.setAssignedTeam(null);
        item.setRoutedTeam(null);
        item.setAssignedAt(null);
        AnalysisResult saved = results.save(item);
        provenance.record(saved.getProvenanceId(), id, ProvenanceEventType.ESCALATED_TO_SENIOR, ProvenanceService.MODERATOR,
                "Escalated to a senior moderator", now);
        audit.record(actor, ModerationAction.ESCALATED_TO_SENIOR, id, null, reason.trim());
        return saved;
    }

    // ------------------------------------------------------------------ wellbeing

    /** Moderator opted to see the item unblurred; counts as an exposure and is logged. */
    @Transactional
    public WellbeingService.Status reveal(Long id, String actor) {
        wellbeing.requireNotOnBreak(actor);
        AnalysisResult item = find(id);
        audit.record(actor, ModerationAction.MEDIA_REVEALED, item.getId(), null, "Revealed unblurred media");
        return wellbeing.recordExposure(actor, item.getId());
    }

    // ------------------------------------------------------------------ helpers

    private void requireCanAct(AnalysisResult item, Moderator me) {
        if (item.isSeniorEscalated() && !me.senior()) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "senior_required",
                    "This item is escalated to a senior moderator.");
        }
        if (item.getAssignedTo() != null && !Objects.equals(item.getAssignedTo(), me.username()) && !me.senior()) {
            throw new ModerationException(HttpStatus.CONFLICT, "assigned_elsewhere",
                    "This item is assigned to " + item.getAssignedTo() + ".");
        }
        requireTeamAccess(item, me);
    }

    /** An unclaimed item routed to a team can only be taken or decided by that team (or a senior). */
    private static void requireTeamAccess(AnalysisResult item, Moderator me) {
        if (item.getAssignedTo() == null && item.getRoutedTeam() != null && !me.senior()
                && !item.getRoutedTeam().equals(me.team())) {
            throw new ModerationException(HttpStatus.CONFLICT, "team_queue",
                    "This item is routed to team " + item.getRoutedTeam() + ".");
        }
    }

    private AnalysisResult find(Long id) {
        return results.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found: " + id));
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
