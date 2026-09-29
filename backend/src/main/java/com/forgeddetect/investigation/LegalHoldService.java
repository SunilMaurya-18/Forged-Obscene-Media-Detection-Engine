package com.forgeddetect.investigation;

import com.forgeddetect.service.CaseNumbers;
import com.forgeddetect.service.ModerationException;
import com.forgeddetect.signing.Ed25519Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 27: legal hold requests to partner platforms, one per platform and set of posts. The request
 * goes to the platform through its own legal channel; this records that it was made, what it covers
 * and what the platform answered. The platform keeps the media; nothing here fetches or stores it.
 */
@Service
public class LegalHoldService {

    static final String PREFIX = "HLD";
    static final int MAX_POSTS = 100;
    private static final int MAX_TEXT = 512;
    private static final int MAX_REFERENCE = 128;

    private final InvestigationService investigations;
    private final CustodyLog custody;
    private final LegalHoldRepository holds;
    private final SightingLinkRepository links;
    private final SightingRepository sightings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LegalHoldService(
            InvestigationService investigations,
            CustodyLog custody,
            LegalHoldRepository holds,
            SightingLinkRepository links,
            SightingRepository sightings,
            Clock clock) {
        this.investigations = investigations;
        this.custody = custody;
        this.holds = holds;
        this.links = links;
        this.sightings = sightings;
        this.clock = clock;
    }

    /**
     * @param postRefs posts to hold; empty means every linked sighting on that platform in this case
     */
    @Transactional
    public LegalHold request(String caseNumber, String platform, Collection<String> postRefs, String note, String actor) {
        Investigation inv = custody.lock(investigations.find(caseNumber));
        String p;
        try {
            p = SightingService.platform(platform);
        } catch (IllegalArgumentException ex) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_hold", ex.getMessage());
        }
        Set<String> onPlatform = sightings.findAllById(links.findByInvestigationId(inv.getId()).stream().map(SightingLink::getSightingId).toList())
                .stream().filter(s -> s.getPlatform().equals(p)).map(Sighting::getPostRef).collect(Collectors.toCollection(TreeSet::new));
        if (onPlatform.isEmpty()) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "no_sightings",
                    "No sighting on " + p + " is linked to " + inv.getCaseNumber() + ", so there is nothing there to hold.");
        }
        TreeSet<String> posts = new TreeSet<>();
        if (postRefs == null || postRefs.isEmpty()) {
            posts.addAll(onPlatform);
        } else {
            for (String raw : postRefs) {
                String ref;
                try {
                    ref = SightingService.postRef(raw);
                } catch (IllegalArgumentException ex) {
                    throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_hold", ex.getMessage());
                }
                if (!onPlatform.contains(ref)) {
                    throw new ModerationException(HttpStatus.BAD_REQUEST, "not_in_case",
                            "Post " + ref + " is not a sighting on " + p + " linked to " + inv.getCaseNumber() + ".");
                }
                posts.add(ref);
            }
        }
        if (posts.size() > MAX_POSTS) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_hold",
                    "One hold covers at most " + MAX_POSTS + " posts; split it into several requests.");
        }
        List<LegalHold> active = holds.findByInvestigationIdOrderByRequestedAtAscIdAsc(inv.getId()).stream()
                .filter(h -> h.getPlatform().equals(p) && h.getStatus().active()).toList();
        Set<String> held = active.stream().flatMap(h -> h.postRefList().stream()).collect(Collectors.toSet());
        if (held.containsAll(posts)) {
            throw new ModerationException(HttpStatus.CONFLICT, "already_held",
                    "Every one of those posts is already covered by " + active.stream().map(LegalHold::getHoldNumber).collect(Collectors.joining(", ")) + ".");
        }

        String r = note == null || note.isBlank() ? null : CustodyLog.clip(note.strip(), MAX_TEXT);
        List<String> sorted = new ArrayList<>(posts);
        LegalHold hold = holds.save(new LegalHold(CaseNumbers.generate(PREFIX, random, holds::existsByHoldNumber), inv.getId(), p,
                sorted, r, CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), now()));
        custody.append(inv, actor, InvestigationEventType.LEGAL_HOLD_REQUESTED, null, null,
                "Legal hold " + hold.getHoldNumber() + " requested from " + p + " for " + sorted.size()
                        + (sorted.size() == 1 ? " post" : " posts") + " (list SHA-256 " + postsHash(sorted) + "): "
                        + String.join(", ", sorted) + (r != null ? ". Note: " + r : ""));
        return hold;
    }

    @Transactional
    public LegalHold acknowledge(String caseNumber, String holdNumber, String partnerReference, String actor) {
        Investigation inv = custody.lock(investigations.find(caseNumber));
        LegalHold hold = find(inv, holdNumber);
        if (hold.getStatus() != LegalHoldStatus.REQUESTED) {
            throw new ModerationException(HttpStatus.CONFLICT, "hold_not_pending",
                    hold.getHoldNumber() + " is " + hold.getStatus().name().toLowerCase(Locale.ROOT) + ", not awaiting the platform's answer.");
        }
        String ref = reference(partnerReference);
        hold.acknowledge(CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), now(), ref);
        holds.save(hold);
        custody.append(inv, actor, InvestigationEventType.LEGAL_HOLD_ACKNOWLEDGED, null, null,
                hold.getPlatform() + " confirmed legal hold " + hold.getHoldNumber()
                        + (ref != null ? " (their reference " + ref + ")" : "") + ".");
        return hold;
    }

    @Transactional
    public LegalHold release(String caseNumber, String holdNumber, String reason, String actor) {
        Investigation inv = custody.lock(investigations.find(caseNumber));
        LegalHold hold = find(inv, holdNumber);
        if (!hold.getStatus().active()) {
            throw new ModerationException(HttpStatus.CONFLICT, "hold_released", hold.getHoldNumber() + " was already released.");
        }
        String r = reason == null || reason.isBlank() ? null : CustodyLog.clip(reason.strip(), MAX_TEXT);
        hold.release(CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), now(), r);
        holds.save(hold);
        custody.append(inv, actor, InvestigationEventType.LEGAL_HOLD_RELEASED, null, null,
                "Legal hold " + hold.getHoldNumber() + " on " + hold.getPlatform() + " released" + (r != null ? ": " + r : "."));
        return hold;
    }

    private LegalHold find(Investigation inv, String holdNumber) {
        String n = CaseNumbers.normalize(PREFIX, holdNumber);
        return (n == null ? Optional.<LegalHold>empty() : holds.findByHoldNumber(n))
                .filter(h -> h.getInvestigationId().equals(inv.getId()))
                .orElseThrow(() -> new ModerationException(HttpStatus.NOT_FOUND, "hold_not_found",
                        "No legal hold " + holdNumber + " in " + inv.getCaseNumber() + "."));
    }

    private static String reference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String r = raw.strip();
        if (r.length() > MAX_REFERENCE || r.chars().anyMatch(Character::isISOControl)) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_hold",
                    "The platform's reference is at most " + MAX_REFERENCE + " characters on one line.");
        }
        return r;
    }

    /** SHA-256 of the sorted post IDs joined by newlines, so the custody event commits to the exact list. */
    static String postsHash(List<String> sorted) {
        return HexFormat.of().formatHex(Ed25519Keys.sha256(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8)));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static Map<String, Object> view(LegalHold h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("holdNumber", h.getHoldNumber());
        m.put("platform", h.getPlatform());
        m.put("postRefs", h.postRefList());
        m.put("status", h.getStatus().name());
        m.put("note", h.getNote());
        m.put("requestedBy", h.getRequestedBy());
        m.put("requestedAt", h.getRequestedAt().toString());
        m.put("acknowledgedBy", h.getAcknowledgedBy());
        m.put("acknowledgedAt", h.getAcknowledgedAt() != null ? h.getAcknowledgedAt().toString() : null);
        m.put("partnerReference", h.getPartnerReference());
        m.put("releasedBy", h.getReleasedBy());
        m.put("releasedAt", h.getReleasedAt() != null ? h.getReleasedAt().toString() : null);
        m.put("releaseReason", h.getReleaseReason());
        return m;
    }
}
