package com.forgeddetect.investigation;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.observability.FmdMetrics;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.PrescreenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 26: records where media was seen. Only a hash (or a provenance ID), a platform name, the
 * partner's opaque post ID and an account pseudonym are kept; the raw account ID is hashed on arrival.
 */
@Service
public class SightingService {

    static final Pattern PLATFORM = Pattern.compile("^[a-z0-9][a-z0-9.\\-]{0,47}$");
    /** Opaque IDs only: no "/" (URLs), "@" (handles, e-mail) or spaces (names). */
    static final Pattern POST_REF = Pattern.compile("^[A-Za-z0-9._:\\-]{1,128}$");
    static final Pattern PROVENANCE_ID = Pattern.compile("^[A-Za-z0-9\\-]{8,64}$");
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /** As received; {@code observedAt} is ISO-8601 and defaults to now. */
    public record Report(String phash, String provenanceId, String platform, String postRef, String accountId, String observedAt) {}

    /** {@code linkedCases}: open investigations the sighting was linked to (never shown to partners). */
    public record Recorded(Sighting sighting, boolean duplicate, int linkedCases) {}

    private final SightingRepository sightings;
    private final AnalysisResultRepository results;
    private final AccountPseudonyms pseudonyms;
    private final InvestigationService investigations;
    private final FmdMetrics metrics;
    private final Clock clock;

    public SightingService(
            SightingRepository sightings,
            AnalysisResultRepository results,
            AccountPseudonyms pseudonyms,
            InvestigationService investigations,
            FmdMetrics metrics,
            Clock clock) {
        this.sightings = sightings;
        this.results = results;
        this.pseudonyms = pseudonyms;
        this.investigations = investigations;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Idempotent per platform + post + hash: reporting the same post again returns the first record.
     *
     * @throws IllegalArgumentException with a message fit to show the caller
     */
    @Transactional
    public Recorded report(Report r, SightingSource source, Long apiClientId, String reportedBy) {
        String platform = platform(r.platform());
        String postRef = postRef(r.postRef());
        String pid = provenanceId(r.provenanceId());
        String phash = r.phash() == null || r.phash().isBlank() ? null : PrescreenService.normalizeHash(r.phash(), "phash");
        if (phash == null && pid == null) {
            throw new IllegalArgumentException("Send phash (16 hex characters), or the provenanceId from a labelled copy's manifest.");
        }
        if (phash == null) {
            phash = results.findByProvenanceId(pid).map(AnalysisResult::getPhash).filter(Hashes::isPhash).orElse(null);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant observed = observedAt(r.observedAt(), now).truncatedTo(ChronoUnit.MICROS);

        Optional<Sighting> existing = sightings.findByDedupeKey(Sighting.dedupeKey(platform, postRef, phash, pid));
        if (existing.isPresent()) {
            return new Recorded(existing.get(), true, 0);
        }
        String accountKey = pseudonyms.of(platform, r.accountId());
        Sighting saved = sightings.save(new Sighting(phash, pid, platform, postRef, accountKey, observed, now, source, apiClientId, reportedBy));
        int linked = investigations.linkNewSighting(saved);
        metrics.sighting(source.name(), linked > 0);
        return new Recorded(saved, false, linked);
    }

    /** Lower case, runs of spaces/underscores become "-". */
    static String platform(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("platform is required (e.g. \"pixelgram\").");
        }
        String p = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        if (!PLATFORM.matcher(p).matches()) {
            throw new IllegalArgumentException("platform must be 1-48 letters, digits, '.' or '-'.");
        }
        return p;
    }

    /** A partner's display name as a platform name, e.g. "Acme Social" becomes "acme-social". */
    public static String platformFromName(String name) {
        String p = name == null ? "" : name.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.\\-]+", "-").replaceAll("^[.\\-]+|[.\\-]+$", "");
        p = p.length() > 48 ? p.substring(0, 48) : p;
        return p.isEmpty() ? "partner" : p;
    }

    static String postRef(String raw) {
        String p = raw == null ? "" : raw.strip();
        if (!POST_REF.matcher(p).matches()) {
            throw new IllegalArgumentException("postRef must be your platform's own post ID: 1-128 letters, digits, "
                    + "'.', '_', ':' or '-'. Don't send URLs, usernames or e-mail addresses.");
        }
        return p;
    }

    private static String provenanceId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String p = raw.strip();
        if (!PROVENANCE_ID.matcher(p).matches()) {
            throw new IllegalArgumentException("provenanceId is not a provenance ID issued by this platform.");
        }
        return p;
    }

    private static Instant observedAt(String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            return now;
        }
        Instant at;
        try {
            at = OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("observedAt must be an ISO-8601 time with an offset, e.g. 2026-09-28T10:15:00Z.");
        }
        if (at.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("observedAt is in the future.");
        }
        return at;
    }
}
