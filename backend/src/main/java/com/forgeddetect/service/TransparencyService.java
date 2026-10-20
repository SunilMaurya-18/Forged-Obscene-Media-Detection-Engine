package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.JurisdictionRule;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.AppealRepository;
import com.forgeddetect.repository.JurisdictionRuleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 23: public transparency report (DSA Art. 15/24 style). Aggregates only: counts and rates,
 * never item IDs, hashes, moderator names or complaint references.
 *
 * <p>Small cells are suppressed: any count from 1 to {@code transparency.min-cell - 1}, and any rate
 * whose denominator is that small, is published as null and listed in {@code suppression.fields}, so
 * comparing reports can't single out one upload or one moderator decision. Windows shorter than
 * {@link #MIN_WINDOW_DAYS} days are refused for the same reason. Reports are cached briefly because the
 * endpoint is public.
 */
@Service
public class TransparencyService {

    static final int MIN_WINDOW_DAYS = 7;
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$");
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");
    private static final Pattern LAST_DAYS = Pattern.compile("^last-(\\d{1,3})d$");

    public record Period(String label, Instant from, Instant to) {}

    private record Cached(Instant at, Map<String, Object> report) {}

    private final AnalysisResultRepository results;
    private final AppealRepository appeals;
    private final JurisdictionRuleRepository rules;
    private final Clock clock;
    private final int minCell;
    private final Duration cacheTtl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TransparencyService(
            AnalysisResultRepository results,
            AppealRepository appeals,
            JurisdictionRuleRepository rules,
            Clock clock,
            @Value("${transparency.min-cell:5}") int minCell,
            @Value("${transparency.cache-ttl:PT60S}") Duration cacheTtl) {
        this.results = results;
        this.appeals = appeals;
        this.rules = rules;
        this.clock = clock;
        this.minCell = Math.max(1, minCell);
        this.cacheTtl = cacheTtl;
    }

    /** {@code YYYY-MM}, {@code YYYY-Qn}, {@code YYYY} or {@code last-Nd} (default last-30d, N at least 7), in UTC. */
    public Period parse(String raw) {
        String input = raw == null || raw.isBlank() ? "last-30d" : raw.trim();
        String p = input.toUpperCase(Locale.ROOT);
        Instant now = clock.instant();
        Matcher m;
        if ((m = LAST_DAYS.matcher(input.toLowerCase(Locale.ROOT))).matches()) {
            p = input.toLowerCase(Locale.ROOT);
            int days = Integer.parseInt(m.group(1));
            if (days < MIN_WINDOW_DAYS || days > 366) {
                throw new IllegalArgumentException("last-Nd accepts " + MIN_WINDOW_DAYS + " to 366 days");
            }
            return new Period(p, now.minus(Duration.ofDays(days)), now);
        }
        if ((m = MONTH.matcher(p)).matches()) {
            YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            return range(p, ym.atDay(1), ym.plusMonths(1).atDay(1));
        }
        if ((m = QUARTER.matcher(p)).matches()) {
            int year = Integer.parseInt(m.group(1));
            int q = Integer.parseInt(m.group(2));
            LocalDate start = LocalDate.of(year, (q - 1) * 3 + 1, 1);
            return range(p, start, start.plusMonths(3));
        }
        if ((m = YEAR.matcher(p)).matches()) {
            int year = Integer.parseInt(m.group(1));
            return range(p, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
        }
        throw new IllegalArgumentException("period must be YYYY-MM, YYYY-Qn, YYYY or last-Nd");
    }

    /** Drops cached reports on this instance (e.g. after underlying rows were purged). */
    public void clearCache() {
        cache.clear();
    }

    private static Period range(String label, LocalDate from, LocalDate to) {
        return new Period(label, from.atStartOfDay().toInstant(ZoneOffset.UTC), to.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> report(Period period) {
        Instant now = clock.instant();
        Cached hit = cache.get(period.label());
        if (hit != null && Duration.between(hit.at(), now).compareTo(cacheTtl) < 0) {
            return hit.report();
        }
        Map<String, Object> built = build(period, now);
        if (cache.size() > 64) {
            cache.clear();
        }
        cache.put(period.label(), new Cached(now, built));
        return built;
    }

    private Map<String, Object> build(Period period, Instant now) {
        List<AnalysisResult> uploads = results.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(period.from(), period.to());
        List<AnalysisResult> complaints =
                results.findByComplaintReceivedAtGreaterThanEqualAndComplaintReceivedAtLessThan(period.from(), period.to());
        List<Appeal> filed = appeals.findByFiledAtGreaterThanEqualAndFiledAtLessThan(period.from(), period.to());

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", period.label());
        p.put("from", period.from().toString());
        p.put("to", period.to().toString());
        out.put("period", p);
        out.put("generatedAt", now.toString());
        out.put("volumes", volumes(uploads, complaints, filed));
        out.put("decisions", decisions(uploads));
        out.put("sla", sla(complaints));
        out.put("appeals", appealStats(filed));
        out.put("moderation", moderation(uploads, filed));

        List<String> suppressed = new ArrayList<>();
        suppress(out, "", minCell, suppressed);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("minCell", minCell);
        s.put("fields", suppressed);
        out.put("suppression", s);

        List<String> notes = new ArrayList<>(List.of(
                "Aggregates only: no item IDs, hashes, moderator names or complaint references are published.",
                "Uploads and decisions are counted by upload time; complaints by receipt time; appeals by filing time (UTC).",
                "Counts from 1 to " + (minCell - 1) + ", and rates computed from so few items, are withheld (null) "
                        + "so that no single upload or decision can be picked out."));
        List<String> pending = pendingReview();
        if (!pending.isEmpty()) {
            notes.add("Legal references for " + String.join(", ", pending) + " are pending legal review.");
        }
        out.put("pendingLegalReview", pending);
        out.put("notes", notes);
        return out;
    }

    private List<String> pendingReview() {
        TreeSet<String> codes = new TreeSet<>();
        for (JurisdictionRule r : rules.findAllByOrderByJurisdictionAscComplaintTypeAsc()) {
            if (r.getLabel() != null && r.getLabel().toLowerCase(Locale.ROOT).contains("pending legal review")) {
                codes.add(r.getJurisdiction());
            }
        }
        return List.copyOf(codes);
    }

    private static Map<String, Object> volumes(List<AnalysisResult> uploads, List<AnalysisResult> complaints, List<Appeal> filed) {
        long videos = uploads.stream().filter(AnalysisResult::isVideo).count();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (ComplaintType t : List.of(ComplaintType.USER_COMPLAINT, ComplaintType.GOVERNMENT_ORDER)) {
            byType.put(t.name(), complaints.stream().filter(r -> r.getComplaintType() == t).count());
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("uploads", (long) uploads.size());
        v.put("images", uploads.size() - videos);
        v.put("videos", videos);
        v.put("apiSubmissions", uploads.stream().filter(r -> r.getApiClientId() != null).count());
        v.put("complaints", (long) complaints.size());
        v.put("complaintsByType", byType);
        v.put("appeals", (long) filed.size());
        return v;
    }

    private static Map<String, Object> decisions(List<AnalysisResult> uploads) {
        Map<Decision, Long> model = new EnumMap<>(Decision.class);
        Map<Decision, Long> current = new EnumMap<>(Decision.class);
        for (Decision d : Decision.values()) {
            model.put(d, 0L);
            current.put(d, 0L);
        }
        for (AnalysisResult r : uploads) {
            Decision m = r.getModelDecision() != null ? r.getModelDecision() : r.getDecision();
            model.merge(m, 1L, Long::sum);
            current.merge(r.getDecision(), 1L, Long::sum);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("automated", names(model));
        d.put("current", names(current));
        d.put("humanReviewed", uploads.stream().filter(r -> r.getReviewedAt() != null).count());
        d.put("pendingSecondReview", uploads.stream().filter(r -> r.getPendingDecision() != null).count());
        return d;
    }

    private Map<String, Object> sla(List<AnalysisResult> complaints) {
        long resolved = 0;
        long timed = 0;
        long within = 0;
        Map<String, long[]> byJurisdiction = new TreeMap<>();
        for (AnalysisResult r : complaints) {
            long[] j = byJurisdiction.computeIfAbsent(String.valueOf(r.getJurisdiction()), k -> new long[4]);
            j[0]++;
            if (r.getResolvedAt() == null) {
                continue;
            }
            resolved++;
            j[1]++;
            if (r.getSlaDeadline() == null) {
                continue;
            }
            timed++;
            j[3]++;
            if (!r.getResolvedAt().isAfter(r.getSlaDeadline())) {
                within++;
                j[2]++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byJurisdiction.forEach((code, c) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jurisdiction", code);
            row.put("received", c[0]);
            row.put("resolved", c[1]);
            row.put("withinDeadline", c[2]);
            row.put("complianceRate", rate(c[2], c[3]));
            rows.add(row);
        });
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("received", (long) complaints.size());
        s.put("resolved", resolved);
        s.put("withinDeadline", within);
        s.put("open", complaints.size() - resolved);
        s.put("complianceRate", rate(within, timed));
        s.put("byJurisdiction", rows);
        return s;
    }

    private Map<String, Object> appealStats(List<Appeal> filed) {
        long open = 0, upheld = 0, granted = 0, reinstated = 0, decided = 0, withinSla = 0;
        List<Long> minutes = new ArrayList<>();
        for (Appeal a : filed) {
            switch (a.getStatus()) {
                case OPEN -> open++;
                case UPHELD -> upheld++;
                case GRANTED -> granted++;
            }
            if (a.getReinstatedAt() != null) {
                reinstated++;
            }
            if (a.getDecidedAt() != null) {
                decided++;
                if (!a.getDecidedAt().isAfter(a.getSlaDeadline())) {
                    withinSla++;
                }
                minutes.add(Duration.between(a.getFiledAt(), a.getDecidedAt()).toMinutes());
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filed", (long) filed.size());
        m.put("open", open);
        m.put("upheld", upheld);
        m.put("granted", granted);
        m.put("reinstated", reinstated);
        m.put("grantRate", rate(granted, upheld + granted));
        m.put("decidedWithinSla", withinSla);
        m.put("slaComplianceRate", rate(withinSla, decided));
        m.put("medianHoursToDecision", minutes.size() < minCell ? null : median(minutes) / 60.0);
        return m;
    }

    static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * Two overturn measures.
     * <ul>
     *   <li>{@code overturnRate}: of the model's definitive decisions (AUTO_FLAG or ALLOW) that a human
     *       later reviewed, the share where the human's final decision differs. REVIEW items are excluded:
     *       the model deferred those, so there is nothing to overturn.</li>
     *   <li>{@code moderatorOverturnRate}: of appeals decided against a moderator's own takedown, the share
     *       granted (the moderator's decision was overturned on appeal).</li>
     * </ul>
     */
    private Map<String, Object> moderation(List<AnalysisResult> uploads, List<Appeal> filed) {
        long reviewed = 0, definitiveReviewed = 0, overturned = 0, flagged = 0, flagOverturned = 0;
        for (AnalysisResult r : uploads) {
            if (r.getReviewedAt() == null || r.getModelDecision() == null) {
                continue;
            }
            reviewed++;
            if (r.getModelDecision() != Decision.REVIEW) {
                definitiveReviewed++;
                if (r.getDecision() != r.getModelDecision()) {
                    overturned++;
                }
            }
            if (r.getModelDecision() == Decision.AUTO_FLAG) {
                flagged++;
                if (r.getDecision() != Decision.AUTO_FLAG) {
                    flagOverturned++;
                }
            }
        }
        long moderatorAppealsDecided = 0, moderatorOverturned = 0;
        for (Appeal a : filed) {
            if (a.getDecidedAt() == null || ProvenanceService.SYSTEM.equals(a.getContestedBy())) {
                continue;
            }
            moderatorAppealsDecided++;
            if (a.getStatus() == Appeal.Status.GRANTED) {
                moderatorOverturned++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("humanReviewed", reviewed);
        m.put("modelDecisionsReviewed", definitiveReviewed);
        m.put("overturned", overturned);
        m.put("overturnRate", rate(overturned, definitiveReviewed));
        m.put("autoFlagReviewed", flagged);
        m.put("autoFlagOverturned", flagOverturned);
        m.put("autoFlagOverturnRate", rate(flagOverturned, flagged));
        m.put("moderatorDecisionsAppealed", moderatorAppealsDecided);
        m.put("moderatorDecisionsOverturned", moderatorOverturned);
        m.put("moderatorOverturnRate", rate(moderatorOverturned, moderatorAppealsDecided));
        return m;
    }

    private static Map<String, Long> names(Map<Decision, Long> counts) {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }

    /** A rate whose denominator is below the minimum cell size is withheld. */
    private Double rate(long n, long d) {
        return d == 0 || d < minCell ? null : (double) n / d;
    }

    /** Nulls every count in [1, minCell) in place, recording its JSON path. Zero stays: it reveals no one. */
    @SuppressWarnings("unchecked")
    static void suppress(Object node, String path, int minCell, List<String> suppressed) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (path.equals(".period") || path.equals(".suppression")) {
                return;
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Object v = e.getValue();
                String p = path + "." + e.getKey();
                if (v instanceof Long || v instanceof Integer) {
                    long n = ((Number) v).longValue();
                    if (n > 0 && n < minCell) {
                        e.setValue(null);
                        suppressed.add(p.substring(1));
                    }
                } else {
                    suppress(v, p, minCell, suppressed);
                }
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                suppress(list.get(i), path + "[" + i + "]", minCell, suppressed);
            }
        }
    }
}
