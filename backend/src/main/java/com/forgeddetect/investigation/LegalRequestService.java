package com.forgeddetect.investigation;

import com.forgeddetect.model.ModeratorAccount;
import com.forgeddetect.repository.ModeratorAccountRepository;
import com.forgeddetect.service.CaseNumbers;
import com.forgeddetect.service.ModerationException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 28: legal requests that let one investigator read named cases. Four eyes: any moderator
 * records a request, and only a senior moderator who did not file it can approve or reject it.
 * Every step is a custody event in each case the request names.
 */
@Service
public class LegalRequestService {

    static final String PREFIX = "LRQ";
    static final int MAX_CASES = 25;
    private static final int MAX_AUTHORITY = 160;
    private static final int MAX_REFERENCE = 128;
    private static final int MAX_TEXT = 512;

    private final InvestigationService investigations;
    private final InvestigationRepository cases;
    private final CustodyLog custody;
    private final LegalRequestRepository requests;
    private final ModeratorAccountRepository accounts;
    private final InvestigationProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LegalRequestService(
            InvestigationService investigations,
            InvestigationRepository cases,
            CustodyLog custody,
            LegalRequestRepository requests,
            ModeratorAccountRepository accounts,
            InvestigationProperties properties,
            Clock clock) {
        this.investigations = investigations;
        this.cases = cases;
        this.custody = custody;
        this.requests = requests;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    public record Filing(String authority, String orderReference, String investigator, List<String> caseNumbers,
            Instant expiresAt, String note) {}

    // ------------------------------------------------------------------ moderator actions

    @Transactional
    public LegalRequest file(Filing f, String actor) {
        if (f == null) {
            throw invalid("Give the authority, its order reference, the investigator, the cases and an expiry.");
        }
        String authority = line(f.authority(), MAX_AUTHORITY, "the issuing authority");
        String reference = line(f.orderReference(), MAX_REFERENCE, "the order reference");
        String investigator = f.investigator() == null ? "" : f.investigator().strip();
        ModeratorAccount account = accounts.findByUsername(investigator).filter(ModeratorAccount::isInvestigator).orElseThrow(() ->
                new ModerationException(HttpStatus.BAD_REQUEST, "unknown_investigator",
                        investigator.isEmpty() ? "Choose the investigator the request is for."
                                : "No investigator account named " + investigator + "."));
        Set<String> numbers = new LinkedHashSet<>(f.caseNumbers() == null ? List.of() : f.caseNumbers());
        numbers.removeIf(n -> n == null || n.isBlank());
        if (numbers.isEmpty() || numbers.size() > MAX_CASES) {
            throw invalid("A request names between 1 and " + MAX_CASES + " cases.");
        }
        List<Investigation> scope = new ArrayList<>();
        for (String n : numbers) {
            Investigation inv = investigations.find(n);
            if (scope.stream().noneMatch(s -> s.getId().equals(inv.getId()))) {
                scope.add(inv);
            }
        }
        Instant now = now();
        Instant expires = f.expiresAt() == null ? null : f.expiresAt().truncatedTo(ChronoUnit.MICROS);
        if (expires == null || !expires.isAfter(now.plus(1, ChronoUnit.HOURS))
                || expires.isAfter(now.plus(properties.getHandover().getMaxRequestTerm()))) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_expiry",
                    "The request must expire between an hour and " + properties.getHandover().getMaxRequestTerm().toDays()
                            + " days from now; access ends then even if nobody revokes it.");
        }
        String note = text(f.note());
        LegalRequest r = requests.save(new LegalRequest(CaseNumbers.generate(PREFIX, random, requests::existsByRequestNumber),
                authority, reference, account.getUsername(), scope.stream().map(Investigation::getId).collect(Collectors.toSet()),
                expires, note, CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), now));
        record(r, actor, InvestigationEventType.LEGAL_REQUEST_FILED,
                "Legal request " + r.getRequestNumber() + " filed: " + authority + ", reference " + reference
                        + ", for investigator " + r.getInvestigator() + ", covering " + count(scope.size())
                        + ", expires " + expires + ". Awaiting approval by a different senior moderator."
                        + (note != null ? " Note: " + note : ""));
        return r;
    }

    @Transactional
    public LegalRequest decide(String requestNumber, boolean approve, String note, String actor, boolean senior) {
        return decide(requestNumber, approve, note, actor, senior, false);
    }

    @Transactional
    public LegalRequest decide(String requestNumber, boolean approve, String note, String actor, boolean senior, boolean admin) {
        LegalRequest r = find(requestNumber);
        if (!senior) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "senior_required",
                    "Only a senior moderator can " + (approve ? "approve" : "reject") + " a legal request.");
        }
        boolean soloAdmin = admin && actor.equals(r.getFiledBy());
        if (actor.equals(r.getFiledBy()) && !admin) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "conflict_of_interest",
                    "You filed " + r.getRequestNumber() + ", so a different senior moderator has to decide it.");
        }
        if (r.getStatus() != LegalRequestStatus.PENDING) {
            throw new ModerationException(HttpStatus.CONFLICT, "request_not_pending",
                    r.getRequestNumber() + " is " + r.getStatus().name().toLowerCase(Locale.ROOT) + ", not awaiting a decision.");
        }
        Instant now = now();
        if (approve && !now.isBefore(r.getExpiresAt())) {
            throw new ModerationException(HttpStatus.CONFLICT, "request_expired",
                    r.getRequestNumber() + " expired at " + r.getExpiresAt() + " before it was approved; file it again.");
        }
        String n = text(note);
        if (!approve && n == null) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "reason_required", "Say why the request is rejected.");
        }
        r.decide(approve, CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), now, n);
        requests.save(r);
        String solo = soloAdmin ? " (owner admin solo-operated the four-eyes check)" : "";
        record(r, actor, approve ? InvestigationEventType.LEGAL_REQUEST_APPROVED : InvestigationEventType.LEGAL_REQUEST_REJECTED,
                approve
                        ? "Legal request " + r.getRequestNumber() + " approved by " + actor + solo + ": " + r.getInvestigator()
                                + " may read this case until " + r.getExpiresAt() + "." + (n != null ? " Note: " + n : "")
                        : "Legal request " + r.getRequestNumber() + " rejected by " + actor + solo + ": " + n);
        return r;
    }

    @Transactional
    public LegalRequest revoke(String requestNumber, String reason, String actor, boolean senior) {
        LegalRequest r = find(requestNumber);
        if (!senior) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "senior_required", "Only a senior moderator can revoke a legal request.");
        }
        if (!r.grantsAccess(now())) {
            throw new ModerationException(HttpStatus.CONFLICT, "request_not_active",
                    r.getRequestNumber() + " is " + r.effectiveStatus(now()).toLowerCase(Locale.ROOT) + "; there is no access to revoke.");
        }
        String n = text(reason);
        if (n == null) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "reason_required", "Say why access is revoked.");
        }
        r.revoke(CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), now(), n);
        requests.save(r);
        record(r, actor, InvestigationEventType.LEGAL_REQUEST_REVOKED,
                "Legal request " + r.getRequestNumber() + " revoked by " + actor + "; " + r.getInvestigator() + " can no longer read this case: " + n);
        return r;
    }

    // ------------------------------------------------------------------ access

    /**
     * The approved, unexpired request that lets {@code investigator} read the case. The refusal is the
     * same whether the case exists or not, so an investigator can't probe for case numbers.
     */
    @Transactional(readOnly = true)
    public Authorised authorise(String investigator, String caseNumber) {
        String n = CaseNumbers.normalize(InvestigationService.PREFIX, caseNumber);
        Instant now = now();
        Optional<Investigation> inv = n == null ? Optional.empty() : cases.findByCaseNumber(n);
        Optional<LegalRequest> grant = inv.flatMap(i -> requests.findCovering(i.getId()).stream()
                .filter(r -> r.getInvestigator().equals(investigator) && r.grantsAccess(now))
                .max(Comparator.comparing(LegalRequest::getExpiresAt)));
        if (grant.isEmpty()) {
            throw new ModerationException(HttpStatus.FORBIDDEN, "no_legal_authority",
                    "You have no approved, unexpired legal request covering " + caseNumber + ".");
        }
        return new Authorised(inv.get(), grant.get());
    }

    public record Authorised(Investigation investigation, LegalRequest request) {}

    /** Requests currently granting {@code investigator} access. */
    @Transactional(readOnly = true)
    public List<LegalRequest> active(String investigator) {
        Instant now = now();
        return requests.findByInvestigatorAndStatusOrderByFiledAtDescIdDesc(investigator, LegalRequestStatus.APPROVED).stream()
                .filter(r -> r.grantsAccess(now)).toList();
    }

    /** IDs of every case {@code investigator} may read right now. */
    @Transactional(readOnly = true)
    public Set<Long> visibleCaseIds(String investigator) {
        return active(investigator).stream().flatMap(r -> r.getCaseIds().stream()).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String viewer, boolean senior) {
        List<LegalRequest> all = requests.findAllByOrderByFiledAtDescIdDesc();
        Map<Long, Investigation> byId = caseMap(all.stream().flatMap(r -> r.getCaseIds().stream()).collect(Collectors.toSet()));
        return all.stream().map(r -> view(r, byId, viewer, senior)).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(LegalRequest r, String viewer, boolean senior) {
        return view(r, caseMap(r.getCaseIds()), viewer, senior);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> investigators() {
        return accounts.findAllByOrderByTeamAscUsernameAsc().stream().filter(ModeratorAccount::isInvestigator)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", a.getUsername());
                    m.put("displayName", a.getDisplayName());
                    m.put("agency", a.getTeam());
                    return m;
                }).toList();
    }

    private Map<String, Object> view(LegalRequest r, Map<Long, Investigation> byId, String viewer, boolean senior) {
        Instant now = now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestNumber", r.getRequestNumber());
        m.put("status", r.effectiveStatus(now));
        m.put("authority", r.getAuthority());
        m.put("orderReference", r.getOrderReference());
        m.put("investigator", r.getInvestigator());
        m.put("cases", r.getCaseIds().stream().map(byId::get).filter(Objects::nonNull)
                .map(c -> Map.of("caseNumber", c.getCaseNumber(), "title", c.getTitle(), "status", c.getStatus().name()))
                .toList());
        m.put("expiresAt", r.getExpiresAt().toString());
        m.put("note", r.getNote());
        m.put("filedBy", r.getFiledBy());
        m.put("filedAt", r.getFiledAt().toString());
        m.put("decidedBy", r.getDecidedBy());
        m.put("decidedAt", str(r.getDecidedAt()));
        m.put("decisionNote", r.getDecisionNote());
        m.put("revokedBy", r.getRevokedBy());
        m.put("revokedAt", str(r.getRevokedAt()));
        m.put("revokeReason", r.getRevokeReason());
        m.put("canDecide", senior && r.getStatus() == LegalRequestStatus.PENDING && !r.getFiledBy().equals(viewer)
                && now.isBefore(r.getExpiresAt()));
        m.put("canRevoke", senior && r.grantsAccess(now));
        return m;
    }

    /** Short forms of the requests naming a case, oldest first; empty if the case doesn't exist. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> covering(String caseNumber) {
        Instant now = now();
        return cases.findByCaseNumber(caseNumber).map(inv -> requests.findCovering(inv.getId()).stream()
                .map(r -> summary(r, now)).toList()).orElse(List.of());
    }

    /** The short form used in a case's custody block. */
    public static Map<String, Object> summary(LegalRequest r, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestNumber", r.getRequestNumber());
        m.put("status", r.effectiveStatus(now));
        m.put("authority", r.getAuthority());
        m.put("orderReference", r.getOrderReference());
        m.put("investigator", r.getInvestigator());
        m.put("expiresAt", r.getExpiresAt().toString());
        m.put("filedBy", r.getFiledBy());
        m.put("decidedBy", r.getDecidedBy());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private LegalRequest find(String requestNumber) {
        String n = CaseNumbers.normalize(PREFIX, requestNumber);
        return (n == null ? Optional.<LegalRequest>empty() : requests.findByRequestNumber(n)).orElseThrow(() ->
                new ModerationException(HttpStatus.NOT_FOUND, "request_not_found", "No legal request " + requestNumber + "."));
    }

    /** One custody event per case, taking the case locks in ID order so two requests can't deadlock. */
    private void record(LegalRequest r, String actor, InvestigationEventType type, String detail) {
        for (Investigation inv : cases.findAllById(r.getCaseIds()).stream().sorted(Comparator.comparing(Investigation::getId)).toList()) {
            custody.append(custody.lock(inv), actor, type, null, null, detail);
        }
    }

    private Map<Long, Investigation> caseMap(Collection<Long> ids) {
        return cases.findAllById(ids).stream().collect(Collectors.toMap(Investigation::getId, Function.identity()));
    }

    private static String line(String raw, int max, String what) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty() || v.length() > max || v.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Give " + what + " on one line, at most " + max + " characters.");
        }
        return v;
    }

    private static String text(String raw) {
        return raw == null || raw.isBlank() ? null : CustodyLog.clip(raw.strip(), MAX_TEXT);
    }

    private static ModerationException invalid(String message) {
        return new ModerationException(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    private static String count(int n) {
        return n + (n == 1 ? " case" : " cases");
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
