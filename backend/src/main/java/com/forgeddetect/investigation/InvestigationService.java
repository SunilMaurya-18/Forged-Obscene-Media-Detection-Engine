package com.forgeddetect.investigation;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ApiClient;
import com.forgeddetect.model.ProvenanceEvent;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ApiClientRepository;
import com.forgeddetect.repository.ProvenanceEventRepository;
import com.forgeddetect.service.CaseNumbers;
import com.forgeddetect.service.ModerationException;
import com.forgeddetect.service.PrescreenProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 26: investigation cases. A case holds one or more analyses; every sighting within the
 * prescreen pHash distance of one of them (or carrying its provenance ID) is linked to the case,
 * whether it was reported before or after the case was opened. Closed cases stop collecting links
 * until they are reopened.
 */
@Service
public class InvestigationService {

    static final String PREFIX = "INV";
    static final String SYSTEM = "system";
    static final String VIA_PROVENANCE = "PROVENANCE";
    static final String VIA_PHASH = "PHASH";
    static final String VIA_LINEAGE = "LINEAGE";
    private static final int MAX_TITLE = 160;
    private static final int MAX_TEXT = 512;
    private static final int MAX_APPEARANCES = 200;

    /** How a sighting matched a case analysis. */
    record Match(AnalysisResult analysis, String via, int distance) {}

    /** Another upload to this platform of a case analysis's media; {@code distance} is null for lineage. */
    record Appearance(AnalysisResult upload, String via, Long matchedAnalysisId, Integer distance) {}

    private final InvestigationRepository cases;
    private final InvestigationItemRepository items;
    private final InvestigationEventRepository events;
    private final SightingRepository sightings;
    private final SightingLinkRepository links;
    private final AnalysisResultRepository results;
    private final ProvenanceEventRepository provenanceEvents;
    private final ApiClientRepository clients;
    private final PrescreenProperties prescreen;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public InvestigationService(
            InvestigationRepository cases,
            InvestigationItemRepository items,
            InvestigationEventRepository events,
            SightingRepository sightings,
            SightingLinkRepository links,
            AnalysisResultRepository results,
            ProvenanceEventRepository provenanceEvents,
            ApiClientRepository clients,
            PrescreenProperties prescreen,
            Clock clock) {
        this.cases = cases;
        this.items = items;
        this.events = events;
        this.sightings = sightings;
        this.links = links;
        this.results = results;
        this.provenanceEvents = provenanceEvents;
        this.clients = clients;
        this.prescreen = prescreen;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public Investigation open(Long analysisId, String title, String actor) {
        AnalysisResult analysis = analysis(analysisId);
        String t = title == null || title.isBlank() ? "Analysis #" + analysisId : clip(title.strip(), MAX_TITLE);
        String number = CaseNumbers.generate(PREFIX, random, cases::existsByCaseNumber);
        Investigation inv = cases.save(new Investigation(number, t, actor, clock.instant()));
        event(inv, actor, InvestigationEventType.OPENED, analysisId, null, "Opened from analysis #" + analysisId);
        attach(inv, analysis, actor);
        return inv;
    }

    @Transactional
    public Investigation addItem(String caseNumber, Long analysisId, String actor) {
        Investigation inv = requireOpen(caseNumber);
        AnalysisResult analysis = analysis(analysisId);
        if (items.existsByInvestigationIdAndAnalysisId(inv.getId(), analysisId)) {
            throw new ModerationException(HttpStatus.CONFLICT, "already_in_case",
                    "Analysis #" + analysisId + " is already part of " + inv.getCaseNumber() + ".");
        }
        attach(inv, analysis, actor);
        return inv;
    }

    @Transactional
    public Investigation note(String caseNumber, String text, String actor) {
        Investigation inv = requireOpen(caseNumber);
        if (text == null || text.isBlank()) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "note_required", "Write the note first.");
        }
        event(inv, actor, InvestigationEventType.NOTE_ADDED, null, null, clip(text.strip(), MAX_TEXT));
        return inv;
    }

    @Transactional
    public Investigation close(String caseNumber, String reason, String actor) {
        Investigation inv = requireOpen(caseNumber);
        String r = reason == null || reason.isBlank() ? null : clip(reason.strip(), MAX_TEXT);
        inv.close(actor, clock.instant(), r);
        cases.save(inv);
        event(inv, actor, InvestigationEventType.CLOSED, null, null, r != null ? "Closed: " + r : "Closed");
        return inv;
    }

    /** Also links sightings reported while the case was closed. */
    @Transactional
    public Investigation reopen(String caseNumber, String actor) {
        Investigation inv = find(caseNumber);
        if (inv.isOpen()) {
            throw new ModerationException(HttpStatus.CONFLICT, "already_open", inv.getCaseNumber() + " is already open.");
        }
        inv.reopen();
        cases.save(inv);
        event(inv, actor, InvestigationEventType.REOPENED, null, null, "Reopened");
        for (InvestigationItem item : items.findByInvestigationIdOrderByAddedAtAscIdAsc(inv.getId())) {
            results.findById(item.getAnalysisId()).ifPresent(a -> linkExisting(inv, a));
        }
        return inv;
    }

    /** pHash and provenance ID of a case analysis (the first one if {@code analysisId} is null), for a moderator-recorded sighting. */
    @Transactional(readOnly = true)
    public AnalysisResult itemForSighting(String caseNumber, Long analysisId) {
        Investigation inv = requireOpen(caseNumber);
        List<InvestigationItem> caseItems = items.findByInvestigationIdOrderByAddedAtAscIdAsc(inv.getId());
        Long id = analysisId != null ? analysisId : caseItems.isEmpty() ? null : caseItems.get(0).getAnalysisId();
        if (id == null || caseItems.stream().noneMatch(i -> i.getAnalysisId().equals(id))) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "not_in_case",
                    "Analysis #" + analysisId + " is not part of " + inv.getCaseNumber() + ".");
        }
        return analysis(id);
    }

    // ------------------------------------------------------------------ linking

    private void attach(Investigation inv, AnalysisResult analysis, String actor) {
        items.save(new InvestigationItem(inv.getId(), analysis.getId(), actor, clock.instant()));
        event(inv, actor, InvestigationEventType.ITEM_ADDED, analysis.getId(), null,
                String.format(Locale.ROOT, "Analysis #%d added (%s, forgery %.2f, explicit %.2f)",
                        analysis.getId(), analysis.getDecision(), analysis.getForgeryScore(), analysis.getExplicitScore()));
        linkExisting(inv, analysis);
    }

    /** Sightings already on record that match this analysis. */
    private void linkExisting(Investigation inv, AnalysisResult analysis) {
        Map<Long, Sighting> candidates = new LinkedHashMap<>();
        if (analysis.getProvenanceId() != null) {
            sightings.findByProvenanceId(analysis.getProvenanceId()).forEach(s -> candidates.put(s.getId(), s));
        }
        for (Sighting s : sightings.findByPhashIsNotNull()) {
            candidates.putIfAbsent(s.getId(), s);
        }
        for (Sighting s : candidates.values()) {
            Match m = match(s, analysis);
            if (m != null) {
                link(inv, s, m);
            }
        }
    }

    /**
     * Links a newly recorded sighting to every open case it matches.
     *
     * @return how many cases it was linked to
     */
    @Transactional
    public int linkNewSighting(Sighting s) {
        List<Investigation> open = cases.findByStatus(InvestigationStatus.OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        Map<Long, List<InvestigationItem>> byCase = items.findByInvestigationIdIn(open.stream().map(Investigation::getId).toList())
                .stream().collect(Collectors.groupingBy(InvestigationItem::getInvestigationId));
        Map<Long, AnalysisResult> analyses = results.findAllById(
                        byCase.values().stream().flatMap(List::stream).map(InvestigationItem::getAnalysisId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AnalysisResult::getId, Function.identity()));
        int linked = 0;
        for (Investigation inv : open) {
            Match best = null;
            for (InvestigationItem item : byCase.getOrDefault(inv.getId(), List.of())) {
                Match m = match(s, analyses.get(item.getAnalysisId()));
                if (m != null && (best == null || m.distance() < best.distance())) {
                    best = m;
                }
            }
            if (best != null && link(inv, s, best)) {
                linked++;
            }
        }
        return linked;
    }

    Match match(Sighting s, AnalysisResult a) {
        if (a == null) {
            return null;
        }
        if (s.getProvenanceId() != null && s.getProvenanceId().equals(a.getProvenanceId())) {
            return new Match(a, VIA_PROVENANCE, 0);
        }
        int d = Hashes.hamming(s.getPhash(), a.getPhash());
        return d >= 0 && d <= prescreen.getPhashMaxDistance() ? new Match(a, VIA_PHASH, d) : null;
    }

    private boolean link(Investigation inv, Sighting s, Match m) {
        if (links.existsByInvestigationIdAndSightingId(inv.getId(), s.getId())) {
            return false;
        }
        links.save(new SightingLink(inv.getId(), s.getId(), m.analysis().getId(), m.via(), m.distance(), clock.instant()));
        String how = VIA_PROVENANCE.equals(m.via()) ? "its embedded provenance ID" : "pHash distance " + m.distance();
        event(inv, s.getReportedBy() != null ? s.getReportedBy() : SYSTEM, InvestigationEventType.SIGHTING_LINKED,
                m.analysis().getId(), s.getId(),
                "Seen on " + s.getPlatform() + " (post " + s.getPostRef() + "), matched to analysis #"
                        + m.analysis().getId() + " by " + how);
        return true;
    }

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, Long analysisId) {
        List<Investigation> found;
        if (analysisId != null) {
            found = cases.findByIdIn(items.findByAnalysisId(analysisId).stream().map(InvestigationItem::getInvestigationId).toList())
                    .stream().sorted(Comparator.comparing(Investigation::getOpenedAt).reversed()).toList();
        } else {
            found = switch (status == null ? "open" : status.toLowerCase(Locale.ROOT)) {
                case "all" -> cases.findAllByOrderByOpenedAtDesc();
                case "closed" -> cases.findByStatusOrderByOpenedAtDesc(InvestigationStatus.CLOSED);
                case "open" -> cases.findByStatusOrderByOpenedAtDesc(InvestigationStatus.OPEN);
                default -> throw new ModerationException(HttpStatus.BAD_REQUEST, "invalid_status", "status must be open, closed or all.");
            };
        }
        return found.stream().map(this::summary).toList();
    }

    /** The list view of one case, or empty if it no longer exists. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> summary(String caseNumber) {
        return cases.findByCaseNumber(caseNumber).map(this::summary);
    }

    private Map<String, Object> summary(Investigation inv) {
        List<Sighting> linked = sightings.findAllById(links.findByInvestigationId(inv.getId()).stream().map(SightingLink::getSightingId).toList());
        Map<String, Object> m = header(inv);
        m.put("items", items.countByInvestigationId(inv.getId()));
        m.put("sightings", linked.size());
        m.put("platforms", linked.stream().map(Sighting::getPlatform).distinct().count());
        m.put("accounts", linked.stream().map(Sighting::getAccountKey).filter(Objects::nonNull).distinct().count());
        m.put("lastActivityAt", events.findTopByInvestigationIdOrderByOccurredAtDescIdDesc(inv.getId())
                .map(e -> e.getOccurredAt().toString()).orElse(inv.getOpenedAt().toString()));
        return m;
    }

    private static Map<String, Object> header(Investigation inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseNumber", inv.getCaseNumber());
        m.put("title", inv.getTitle());
        m.put("status", inv.getStatus().name());
        m.put("openedBy", inv.getOpenedBy());
        m.put("openedAt", inv.getOpenedAt().toString());
        m.put("closedBy", inv.getClosedBy());
        m.put("closedAt", str(inv.getClosedAt()));
        m.put("closeReason", inv.getCloseReason());
        return m;
    }

    /**
     * The whole case: evidence (the analyses), where the media appeared (on this platform and in
     * partner sightings), a per-platform summary, the account pseudonyms involved and one timeline.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String caseNumber) {
        Investigation inv = find(caseNumber);
        List<InvestigationItem> caseItems = items.findByInvestigationIdOrderByAddedAtAscIdAsc(inv.getId());
        Map<Long, AnalysisResult> analyses = results.findAllById(caseItems.stream().map(InvestigationItem::getAnalysisId).toList())
                .stream().collect(Collectors.toMap(AnalysisResult::getId, Function.identity()));
        List<SightingLink> caseLinks = links.findByInvestigationId(inv.getId());
        Map<Long, Sighting> linked = sightings.findAllById(caseLinks.stream().map(SightingLink::getSightingId).toList())
                .stream().collect(Collectors.toMap(Sighting::getId, Function.identity()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (InvestigationItem item : caseItems) {
            AnalysisResult a = analyses.get(item.getAnalysisId());
            if (a != null) {
                evidence.add(evidenceView(a, item));
            }
        }

        List<Appearance> appearances = appearances(analyses.values());
        Set<Long> clientIds = new HashSet<>();
        linked.values().forEach(s -> clientIds.add(s.getApiClientId()));
        appearances.forEach(a -> clientIds.add(a.upload().getApiClientId()));
        Map<Long, String> partnerNames = clientNames(clientIds);

        List<Map<String, Object>> sightingViews = caseLinks.stream()
                .filter(l -> linked.containsKey(l.getSightingId()))
                .sorted(Comparator.comparing((SightingLink l) -> linked.get(l.getSightingId()).getObservedAt()))
                .map(l -> sightingView(linked.get(l.getSightingId()), l, partnerNames))
                .toList();

        Map<String, Object> body = header(inv);
        body.put("evidence", evidence);
        body.put("appearances", appearances.stream().map(m -> appearanceView(m, partnerNames)).toList());
        body.put("sightings", sightingViews);
        body.put("platforms", platforms(linked.values()));
        body.put("accounts", accounts(inv, linked.values()));
        body.put("timeline", timeline(inv, analyses.values(), appearances, caseLinks, linked));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("evidence", evidence.size());
        counts.put("appearances", appearances.size());
        counts.put("sightings", sightingViews.size());
        counts.put("platforms", linked.values().stream().map(Sighting::getPlatform).distinct().count());
        counts.put("accounts", linked.values().stream().map(Sighting::getAccountKey).filter(Objects::nonNull).distinct().count());
        body.put("counts", counts);
        body.put("phashMaxDistance", prescreen.getPhashMaxDistance());
        return body;
    }

    private Map<String, Object> evidenceView(AnalysisResult a, InvestigationItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.getId());
        m.put("provenanceId", a.getProvenanceId());
        m.put("createdAt", str(a.getCreatedAt()));
        m.put("mediaType", a.getMediaType());
        m.put("decision", a.getDecision().name());
        m.put("modelDecision", a.getModelDecision() != null ? a.getModelDecision().name() : null);
        m.put("decidedBy", a.getDecidedBy());
        m.put("forgeryScore", a.getForgeryScore());
        m.put("explicitScore", a.getExplicitScore());
        m.put("generationFamily", a.getGenerationFamily());
        m.put("familyConfidence", a.getFamilyConfidence());
        m.put("phash", a.getPhash());
        m.put("contentHash", a.getContentHash());
        m.put("hasForensics", a.getForensicSignature() != null);
        m.put("hasContentCredentials", a.getC2paManifest() != null);
        m.put("complaintType", a.getComplaintType() != null ? a.getComplaintType().name() : null);
        m.put("slaStatus", a.getSlaStatus() != null ? a.getSlaStatus().name() : null);
        m.put("appealStatus", a.getAppealStatus());
        m.put("reportId", a.getReportId());
        m.put("reportSignedAt", str(a.getReportSignedAt()));
        m.put("reportSigningKeyId", a.getReportSigningKeyId());
        m.put("addedBy", item.getAddedBy());
        m.put("addedAt", item.getAddedAt().toString());
        return m;
    }

    /** Other uploads to this platform of the same media: re-uploads traced to a case analysis, or within the pHash distance. */
    private List<Appearance> appearances(Collection<AnalysisResult> caseAnalyses) {
        if (caseAnalyses.isEmpty()) {
            return List.of();
        }
        Set<Long> caseIds = caseAnalyses.stream().map(AnalysisResult::getId).collect(Collectors.toSet());
        Map<String, AnalysisResult> byProvenance = new HashMap<>();
        caseAnalyses.stream().filter(a -> a.getProvenanceId() != null).forEach(a -> byProvenance.put(a.getProvenanceId(), a));
        Map<Long, Match> found = new LinkedHashMap<>();
        for (Object[] row : results.hashIndex()) {
            Long id = (Long) row[0];
            if (caseIds.contains(id)) {
                continue;
            }
            AnalysisResult source = row[2] != null ? byProvenance.get((String) row[2]) : null;
            if (source != null) {
                found.put(id, new Match(source, VIA_LINEAGE, -1));
                continue;
            }
            Match best = null;
            for (AnalysisResult a : caseAnalyses) {
                int d = Hashes.hamming((String) row[1], a.getPhash());
                if (d >= 0 && d <= prescreen.getPhashMaxDistance() && (best == null || d < best.distance())) {
                    best = new Match(a, VIA_PHASH, d);
                }
            }
            if (best != null) {
                found.put(id, best);
            }
        }
        List<Appearance> out = new ArrayList<>();
        for (AnalysisResult upload : results.findAllById(found.keySet())) {
            Match m = found.get(upload.getId());
            out.add(new Appearance(upload, m.via(), m.analysis().getId(), m.distance() >= 0 ? m.distance() : null));
        }
        out.sort(Comparator.comparing((Appearance a) -> a.upload().getCreatedAt()).reversed());
        return out.size() > MAX_APPEARANCES ? out.subList(0, MAX_APPEARANCES) : out;
    }

    private static Map<String, Object> appearanceView(Appearance ap, Map<Long, String> partnerNames) {
        AnalysisResult a = ap.upload();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("analysisId", a.getId());
        v.put("provenanceId", a.getProvenanceId());
        v.put("createdAt", str(a.getCreatedAt()));
        v.put("decision", a.getDecision().name());
        v.put("forgeryScore", a.getForgeryScore());
        v.put("channel", a.getApiClientId() != null ? "PARTNER_API" : "DIRECT");
        v.put("partner", a.getApiClientId() != null ? partnerNames.get(a.getApiClientId()) : null);
        v.put("via", ap.via());
        v.put("matchedAnalysisId", ap.matchedAnalysisId());
        v.put("distance", ap.distance());
        return v;
    }

    private static Map<String, Object> sightingView(Sighting s, SightingLink l, Map<Long, String> partnerNames) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", s.getId());
        v.put("platform", s.getPlatform());
        v.put("postRef", s.getPostRef());
        v.put("account", AccountPseudonyms.shortForm(s.getAccountKey()));
        v.put("observedAt", s.getObservedAt().toString());
        v.put("reportedAt", s.getReportedAt().toString());
        v.put("source", s.getSource().name());
        v.put("reporter", s.getSource() == SightingSource.MODERATOR ? s.getReportedBy() : partnerNames.get(s.getApiClientId()));
        v.put("via", l.getVia());
        v.put("distance", l.getDistance());
        v.put("matchedAnalysisId", l.getAnalysisId());
        v.put("linkedAt", l.getLinkedAt().toString());
        return v;
    }

    private static List<Map<String, Object>> platforms(Collection<Sighting> linked) {
        Map<String, List<Sighting>> byPlatform = linked.stream().collect(Collectors.groupingBy(Sighting::getPlatform));
        return byPlatform.entrySet().stream()
                .map(e -> {
                    List<Sighting> list = e.getValue();
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("platform", e.getKey());
                    v.put("sightings", list.size());
                    v.put("posts", list.stream().map(Sighting::getPostRef).distinct().count());
                    v.put("accounts", list.stream().map(Sighting::getAccountKey).filter(Objects::nonNull).distinct().count());
                    v.put("firstSeen", list.stream().map(Sighting::getObservedAt).min(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
                    v.put("lastSeen", list.stream().map(Sighting::getObservedAt).max(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
                    return v;
                })
                .sorted(Comparator.comparing((Map<String, Object> v) -> Instant.parse((String) v.get("firstSeen"))))
                .toList();
    }

    /**
     * One entry per account pseudonym in the case: how often and where it posted this media, how many
     * sightings it has on record overall, and which other cases it appears in.
     */
    private List<Map<String, Object>> accounts(Investigation inv, Collection<Sighting> linked) {
        Map<String, List<Sighting>> byAccount = linked.stream()
                .filter(s -> s.getAccountKey() != null)
                .collect(Collectors.groupingBy(Sighting::getAccountKey));
        if (byAccount.isEmpty()) {
            return List.of();
        }
        List<Sighting> everywhere = sightings.findByAccountKeyIn(byAccount.keySet());
        Map<Long, String> keyOfSighting = everywhere.stream().collect(Collectors.toMap(Sighting::getId, Sighting::getAccountKey));
        Map<String, Long> totals = everywhere.stream().collect(Collectors.groupingBy(Sighting::getAccountKey, Collectors.counting()));
        Map<String, Set<Long>> otherCaseIds = new HashMap<>();
        for (SightingLink l : links.findBySightingIdIn(keyOfSighting.keySet())) {
            if (!l.getInvestigationId().equals(inv.getId())) {
                otherCaseIds.computeIfAbsent(keyOfSighting.get(l.getSightingId()), k -> new HashSet<>()).add(l.getInvestigationId());
            }
        }
        Map<Long, Investigation> others = cases.findByIdIn(otherCaseIds.values().stream().flatMap(Set::stream).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Investigation::getId, Function.identity()));

        return byAccount.entrySet().stream()
                .map(e -> {
                    List<Sighting> list = e.getValue();
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("account", AccountPseudonyms.shortForm(e.getKey()));
                    v.put("platform", list.get(0).getPlatform());
                    v.put("sightings", list.size());
                    v.put("posts", list.stream().map(Sighting::getPostRef).distinct().count());
                    v.put("totalSightings", totals.getOrDefault(e.getKey(), (long) list.size()));
                    v.put("firstSeen", list.stream().map(Sighting::getObservedAt).min(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
                    v.put("lastSeen", list.stream().map(Sighting::getObservedAt).max(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
                    v.put("otherCases", otherCaseIds.getOrDefault(e.getKey(), Set.of()).stream()
                            .map(others::get)
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(Investigation::getOpenedAt))
                            .map(o -> Map.of("caseNumber", o.getCaseNumber(), "status", o.getStatus().name(), "title", o.getTitle()))
                            .toList());
                    return v;
                })
                .sorted(Comparator.comparing((Map<String, Object> v) -> ((Number) v.get("sightings")).longValue()).reversed()
                        .thenComparing(v -> (String) v.get("firstSeen")))
                .toList();
    }

    /** Case log, provenance events of the case analyses, when each sighting was observed and other uploads here. */
    private List<Map<String, Object>> timeline(
            Investigation inv,
            Collection<AnalysisResult> caseAnalyses,
            List<Appearance> appearances,
            List<SightingLink> caseLinks,
            Map<Long, Sighting> linked) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvestigationEvent e : events.findByInvestigationIdOrderByOccurredAtAscIdAsc(inv.getId())) {
            out.add(entry(e.getOccurredAt(), "case", e.getType().name(), e.getActor(), e.getDetail(), e.getAnalysisId(), e.getSightingId()));
        }
        Set<String> seen = new TreeSet<>();
        for (AnalysisResult a : caseAnalyses) {
            if (a.getProvenanceId() == null || !seen.add(a.getProvenanceId())) {
                continue;
            }
            for (ProvenanceEvent p : provenanceEvents.findByProvenanceIdOrderByOccurredAtAscIdAsc(a.getProvenanceId())) {
                out.add(entry(p.getOccurredAt(), "provenance", p.getType().name(), p.getActor(), p.getDetail(), p.getAnalysisId(), null));
            }
        }
        for (SightingLink l : caseLinks) {
            Sighting s = linked.get(l.getSightingId());
            if (s != null) {
                out.add(entry(s.getObservedAt(), "sighting", "OBSERVED", s.getSource() == SightingSource.MODERATOR ? s.getReportedBy() : "partner",
                        "Observed on " + s.getPlatform() + " (post " + s.getPostRef() + ")"
                                + (s.getAccountKey() != null ? " by account " + AccountPseudonyms.shortForm(s.getAccountKey()) : ""),
                        l.getAnalysisId(), s.getId()));
            }
        }
        // Lineage re-uploads are already in the provenance trail as REUPLOAD_DETECTED
        for (Appearance ap : appearances) {
            if (VIA_PHASH.equals(ap.via())) {
                out.add(entry(ap.upload().getCreatedAt(), "appearance", "UPLOADED", SYSTEM,
                        "Uploaded here again as analysis #" + ap.upload().getId() + " (pHash distance " + ap.distance() + ")",
                        ap.upload().getId(), null));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> v) -> Instant.parse((String) v.get("at"))));
        return out;
    }

    private static Map<String, Object> entry(Instant at, String kind, String type, String actor, String detail, Long analysisId, Long sightingId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("at", at.toString());
        v.put("kind", kind);
        v.put("type", type);
        v.put("actor", actor);
        v.put("detail", detail);
        v.put("analysisId", analysisId);
        v.put("sightingId", sightingId);
        return v;
    }

    private Map<Long, String> clientNames(Set<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> out = new HashMap<>();
        if (!wanted.isEmpty()) {
            for (ApiClient c : clients.findAllById(wanted)) {
                out.put(c.getId(), c.getName());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private AnalysisResult analysis(Long id) {
        if (id == null) {
            throw new ModerationException(HttpStatus.BAD_REQUEST, "analysis_required", "Choose the analysis to investigate.");
        }
        return results.findById(id).orElseThrow(() ->
                new ModerationException(HttpStatus.NOT_FOUND, "analysis_not_found", "Analysis #" + id + " does not exist."));
    }

    public Investigation find(String caseNumber) {
        String n = CaseNumbers.normalize(PREFIX, caseNumber);
        return (n == null ? Optional.<Investigation>empty() : cases.findByCaseNumber(n)).orElseThrow(() ->
                new ModerationException(HttpStatus.NOT_FOUND, "case_not_found", "No investigation " + caseNumber + "."));
    }

    private Investigation requireOpen(String caseNumber) {
        Investigation inv = find(caseNumber);
        if (!inv.isOpen()) {
            throw new ModerationException(HttpStatus.CONFLICT, "case_closed",
                    inv.getCaseNumber() + " is closed. Reopen it to make changes.");
        }
        return inv;
    }

    private void event(Investigation inv, String actor, InvestigationEventType type, Long analysisId, Long sightingId, String detail) {
        events.save(new InvestigationEvent(inv.getId(), clock.instant(), actor, type, analysisId, sightingId, clip(detail, MAX_TEXT)));
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }
}
