package com.forgeddetect.investigation;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.CaseNumbers;
import com.forgeddetect.service.ModerationException;
import com.forgeddetect.signing.Ed25519Keys;
import com.forgeddetect.signing.ReportTimestampService;
import com.forgeddetect.signing.SigningKeyProvider;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 27: "Preserve evidence". Writes a canonical manifest of what the case holds (per analysis:
 * content SHA-256, pHash, scores and the signed report's ID and hash; per linked sighting: platform,
 * post, account pseudonym and how it matched; and the custody head), signs its SHA-256 with the
 * report-signing key and, when a TSA is configured, timestamps the signature.
 *
 * <p>Verification rebuilds the manifest from the records it names, as they are now, and diffs it
 * against the signed one. Later additions to the case don't affect an earlier preservation; a
 * change to anything it covers, or to the custody log up to its head, makes it {@code TAMPERED}.
 */
@Service
public class PreservationService {

    static final String VERSION = "fmd-preservation-manifest/v1";
    static final String PREFIX = "PRS";
    static final int MAX_MANIFEST = 1_000_000;
    private static final int MAX_REASON = 512;

    public enum Status { VALID, TAMPERED }

    /** {@code timestampStatus}: STAMPED, UNAVAILABLE (TSA failed) or DISABLED, as it went at preservation time. */
    public record Preserved(Preservation preservation, String timestampStatus, String timestampError) {}

    public record Verification(
            Status status, String message, Preservation preservation, String caseNumber, String keyStatus,
            List<String> changedFields, ReportTimestampService.Check timestamp) {}

    private final InvestigationService investigations;
    private final CustodyLog custody;
    private final PreservationRepository preservations;
    private final InvestigationItemRepository items;
    private final SightingLinkRepository links;
    private final SightingRepository sightings;
    private final AnalysisResultRepository results;
    private final SigningKeyProvider keys;
    private final ReportTimestampService timestamps;
    private final Clock clock;
    private final EntityManager em;
    private final SecureRandom random = new SecureRandom();

    public PreservationService(
            InvestigationService investigations,
            CustodyLog custody,
            PreservationRepository preservations,
            InvestigationItemRepository items,
            SightingLinkRepository links,
            SightingRepository sightings,
            AnalysisResultRepository results,
            SigningKeyProvider keys,
            ReportTimestampService timestamps,
            Clock clock,
            EntityManager em) {
        this.investigations = investigations;
        this.custody = custody;
        this.preservations = preservations;
        this.items = items;
        this.links = links;
        this.sightings = sightings;
        this.results = results;
        this.keys = keys;
        this.timestamps = timestamps;
        this.clock = clock;
        this.em = em;
    }

    /** Allowed on closed cases too: preserving the final state is the usual reason to preserve. */
    @Transactional
    public Preserved preserve(String caseNumber, String reason, String actor) {
        // The database rounds timestamps to microseconds; sign the records as stored, not as a caller in
        // the same transaction still holds them
        em.flush();
        em.clear();
        Investigation inv = custody.lock(investigations.find(caseNumber));
        CustodyChain.Anchor head = custody.head(inv);
        CustodyChain.Result chain = custody.verify(inv);
        if (!chain.valid()) {
            throw new ModerationException(HttpStatus.CONFLICT, "custody_broken",
                    "The custody log of " + inv.getCaseNumber() + " fails verification, so a preservation would vouch for a broken record: "
                            + chain.problem());
        }
        List<Long> analysisIds = items.findByInvestigationIdOrderByAddedAtAscIdAsc(inv.getId()).stream()
                .map(InvestigationItem::getAnalysisId).sorted().toList();
        List<Long> sightingIds = links.findByInvestigationId(inv.getId()).stream()
                .map(SightingLink::getSightingId).sorted().toList();
        String r = reason == null || reason.isBlank() ? null : CustodyLog.clip(reason.strip(), MAX_REASON);

        Preservation p = new Preservation(
                CaseNumbers.generate(PREFIX, random, preservations::existsByPreservationId), inv.getId(),
                clock.instant().truncatedTo(ChronoUnit.MICROS), CustodyLog.clip(actor, CustodyLog.MAX_ACTOR), r,
                head.length(), head.head(), analysisIds.size(), sightingIds.size());
        String manifest = manifest(p, inv, head.head(), analysisIds, sightingIds);
        if (manifest.length() > MAX_MANIFEST) {
            throw new ModerationException(HttpStatus.UNPROCESSABLE_ENTITY, "manifest_too_large",
                    "This case has too many sightings to preserve in one manifest (" + sightingIds.size() + ").");
        }
        byte[] digest = Ed25519Keys.sha256(manifest.getBytes(StandardCharsets.UTF_8));
        SigningKeyProvider.SignatureResult signed = keys.sign(digest);
        String hash = HexFormat.of().formatHex(digest);
        p.sign(manifest, hash, Base64.getEncoder().encodeToString(signed.signature()), signed.keyId());

        ReportTimestampService.Outcome stamped = timestamps.stamp(signed.signature());
        String tsStatus;
        if (stamped.stamp() != null) {
            p.timestamp(stamped.stamp().tokenBase64(), stamped.stamp().time(), CustodyLog.clip(stamped.stamp().authority(), 255));
            tsStatus = "STAMPED";
        } else {
            tsStatus = timestamps.enabled() ? "UNAVAILABLE" : "DISABLED";
        }
        preservations.save(p);
        custody.append(inv, actor, InvestigationEventType.PRESERVED, null, null,
                String.format(Locale.ROOT, "Evidence preserved as %s: %d %s, %d %s, custody log up to event %d. Manifest SHA-256 %s%s",
                        p.getPreservationId(), analysisIds.size(), analysisIds.size() == 1 ? "analysis" : "analyses",
                        sightingIds.size(), sightingIds.size() == 1 ? "sighting" : "sightings", head.length(), hash,
                        r != null ? ". Reason: " + r : ""));
        return new Preserved(p, tsStatus, stamped.stamp() == null && timestamps.enabled() ? stamped.error() : null);
    }

    @Transactional(readOnly = true)
    public Preservation find(String caseNumber, String preservationId) {
        Investigation inv = investigations.find(caseNumber);
        String id = CaseNumbers.normalize(PREFIX, preservationId);
        return (id == null ? Optional.<Preservation>empty() : preservations.findByPreservationId(id))
                .filter(p -> p.getInvestigationId().equals(inv.getId()))
                .orElseThrow(() -> new ModerationException(HttpStatus.NOT_FOUND, "preservation_not_found",
                        "No preservation " + preservationId + " in " + inv.getCaseNumber() + "."));
    }

    @Transactional(readOnly = true)
    public List<Preservation> list(Investigation inv) {
        return preservations.findByInvestigationIdOrderByPreservedAtAscIdAsc(inv.getId());
    }

    @Transactional(readOnly = true)
    public Verification verify(String caseNumber, String preservationId) {
        Investigation inv = investigations.find(caseNumber);
        Preservation p = find(caseNumber, preservationId);
        Optional<PublicKey> key = keys.publicKey(p.getSigningKeyId());
        if (key.isEmpty()) {
            return tampered(p, inv, "Signed with key " + p.getSigningKeyId() + ", which is not in this server's keyring. "
                    + "The signature can't be trusted.", List.of("signing_key"), "UNKNOWN", null);
        }
        String keyStatus = p.getSigningKeyId().equals(keys.activeKeyId()) ? "ACTIVE" : "RETIRED";
        byte[] digest = Ed25519Keys.sha256(p.getManifest().getBytes(StandardCharsets.UTF_8));
        byte[] signature = decode(p.getSignature());
        if (!HexFormat.of().formatHex(digest).equals(p.getManifestHash()) || signature == null
                || !Ed25519Keys.verify(key.get(), digest, signature)) {
            return tampered(p, inv, "The stored signature does not match the stored manifest: the preservation record itself was altered.",
                    List.of("signature"), keyStatus, null);
        }
        ReportTimestampService.Check ts = p.getTimestampToken() == null ? null : timestamps.verify(p.getTimestampToken(), signature);
        if (ts != null && ts.status() != ReportTimestampService.CheckStatus.VERIFIED) {
            return tampered(p, inv, "The signature is genuine, but its RFC 3161 timestamp does not check out: " + ts.message(),
                    List.of("timestamp"), keyStatus, ts);
        }

        Map<String, String> signed = parse(p.getManifest());
        List<Long> analysisIds = ids(signed, "analysis");
        List<Long> sightingIds = ids(signed, "sighting");
        String head = custody.recomputeHead(inv, p.getCustodyLength());
        String current = manifest(p, inv, head, analysisIds, sightingIds);
        List<String> changed = diff(signed, parse(current));
        if (!changed.isEmpty()) {
            return tampered(p, inv, "The signature is genuine, but the records no longer match what was preserved. Changed since: "
                    + String.join(", ", changed) + ".", changed, keyStatus, ts);
        }
        String tsNote = ts != null ? " Timestamped " + ts.time() + " by " + ts.authority() + "." : "";
        return new Verification(Status.VALID,
                "Signature verified with " + Ed25519Keys.ALGORITHM + " key " + p.getSigningKeyId()
                        + ". Every analysis, report, sighting and custody event it covers still matches the manifest." + tsNote,
                p, inv.getCaseNumber(), keyStatus, List.of(), ts);
    }

    // ------------------------------------------------------------------ manifest

    /**
     * Fixed field order, one {@code key=value} per line, UTF-8; analyses and sightings in ID order.
     * Changing this means a new version string.
     */
    String manifest(Preservation p, Investigation inv, String custodyHead, List<Long> analysisIds, List<Long> sightingIds) {
        Map<Long, AnalysisResult> analyses = results.findAllById(analysisIds).stream()
                .collect(Collectors.toMap(AnalysisResult::getId, Function.identity()));
        Map<Long, InvestigationItem> caseItems = items.findByInvestigationIdOrderByAddedAtAscIdAsc(inv.getId()).stream()
                .collect(Collectors.toMap(InvestigationItem::getAnalysisId, Function.identity(), (a, b) -> a));
        Map<Long, Sighting> seen = sightings.findAllById(sightingIds).stream()
                .collect(Collectors.toMap(Sighting::getId, Function.identity()));
        Map<Long, SightingLink> caseLinks = links.findByInvestigationId(inv.getId()).stream()
                .collect(Collectors.toMap(SightingLink::getSightingId, Function.identity(), (a, b) -> a));

        Map<String, String> f = new LinkedHashMap<>();
        f.put("preservation_id", p.getPreservationId());
        f.put("case_number", inv.getCaseNumber());
        f.put("case_title", inv.getTitle());
        f.put("case_opened_at", time(inv.getOpenedAt()));
        f.put("case_opened_by", inv.getOpenedBy());
        f.put("preserved_at", time(p.getPreservedAt()));
        f.put("preserved_by", p.getPreservedBy());
        f.put("reason", p.getReason());
        f.put("custody_length", String.valueOf(p.getCustodyLength()));
        f.put("custody_head", custodyHead);
        f.put("analysis_count", String.valueOf(analysisIds.size()));
        int n = 0;
        for (Long id : analysisIds) {
            String k = "analysis." + (++n) + ".";
            AnalysisResult a = analyses.get(id);
            InvestigationItem item = caseItems.get(id);
            f.put(k + "id", String.valueOf(id));
            f.put(k + "provenance_id", a != null ? a.getProvenanceId() : null);
            f.put(k + "analysed_at", a != null ? time(a.getCreatedAt()) : null);
            f.put(k + "media_type", a != null ? a.getMediaType() : null);
            f.put(k + "decision", a != null && a.getDecision() != null ? a.getDecision().name() : null);
            f.put(k + "forgery_score", a != null ? String.format(Locale.ROOT, "%.6f", a.getForgeryScore()) : null);
            f.put(k + "explicit_score", a != null ? String.format(Locale.ROOT, "%.6f", a.getExplicitScore()) : null);
            f.put(k + "generation_family", a != null ? a.getGenerationFamily() : null);
            f.put(k + "phash", a != null ? a.getPhash() : null);
            f.put(k + "content_sha256", a != null ? a.getContentHash() : null);
            f.put(k + "report_id", a != null ? a.getReportId() : null);
            f.put(k + "report_signed_at", a != null ? time(a.getReportSignedAt()) : null);
            f.put(k + "report_sha256", a != null ? a.getReportContentHash() : null);
            f.put(k + "report_signing_key_id", a != null ? a.getReportSigningKeyId() : null);
            f.put(k + "added_by", item != null ? item.getAddedBy() : null);
            f.put(k + "added_at", item != null ? time(item.getAddedAt()) : null);
        }
        f.put("sighting_count", String.valueOf(sightingIds.size()));
        n = 0;
        for (Long id : sightingIds) {
            String k = "sighting." + (++n) + ".";
            Sighting s = seen.get(id);
            SightingLink l = caseLinks.get(id);
            f.put(k + "id", String.valueOf(id));
            f.put(k + "platform", s != null ? s.getPlatform() : null);
            f.put(k + "post_ref", s != null ? s.getPostRef() : null);
            f.put(k + "account", s != null ? s.getAccountKey() : null);
            f.put(k + "phash", s != null ? s.getPhash() : null);
            f.put(k + "provenance_id", s != null ? s.getProvenanceId() : null);
            f.put(k + "observed_at", s != null ? time(s.getObservedAt()) : null);
            f.put(k + "reported_at", s != null ? time(s.getReportedAt()) : null);
            f.put(k + "source", s != null && s.getSource() != null ? s.getSource().name() : null);
            f.put(k + "matched_analysis_id", l != null ? String.valueOf(l.getAnalysisId()) : null);
            f.put(k + "via", l != null ? l.getVia() : null);
            f.put(k + "distance", l != null ? String.valueOf(l.getDistance()) : null);
            f.put(k + "linked_at", l != null ? time(l.getLinkedAt()) : null);
        }
        StringBuilder sb = new StringBuilder(VERSION).append('\n');
        f.forEach((k, v) -> sb.append(k).append('=').append(CustodyChain.escape(v)).append('\n'));
        return sb.toString();
    }

    private static List<Long> ids(Map<String, String> manifest, String kind) {
        List<Long> out = new ArrayList<>();
        for (int i = 1; manifest.containsKey(kind + "." + i + ".id"); i++) {
            out.add(Long.parseLong(manifest.get(kind + "." + i + ".id")));
        }
        return out;
    }

    static Map<String, String> parse(String manifest) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = manifest.split("\n");
        for (int i = 1; i < lines.length; i++) {
            int eq = lines[i].indexOf('=');
            if (eq > 0) {
                out.put(lines[i].substring(0, eq), lines[i].substring(eq + 1));
            }
        }
        return out;
    }

    private static List<String> diff(Map<String, String> signed, Map<String, String> current) {
        List<String> changed = new ArrayList<>();
        signed.forEach((k, v) -> {
            if (!Objects.equals(v, current.get(k))) {
                changed.add(k);
            }
        });
        current.keySet().stream().filter(k -> !signed.containsKey(k)).forEach(changed::add);
        return changed;
    }

    /** Microseconds, the precision timestamps are stored with. */
    private static String time(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static byte[] decode(String b64) {
        try {
            return b64 == null ? null : Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Verification tampered(
            Preservation p, Investigation inv, String message, List<String> changed, String keyStatus, ReportTimestampService.Check ts) {
        return new Verification(Status.TAMPERED, message, p, inv.getCaseNumber(), keyStatus, changed, ts);
    }

    // ------------------------------------------------------------------ views

    public static Map<String, Object> view(Preservation p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preservationId", p.getPreservationId());
        m.put("preservedAt", p.getPreservedAt().toString());
        m.put("preservedBy", p.getPreservedBy());
        m.put("reason", p.getReason());
        m.put("analyses", p.getAnalysisCount());
        m.put("sightings", p.getSightingCount());
        m.put("custodyLength", p.getCustodyLength());
        m.put("custodyHead", p.getCustodyHead());
        m.put("manifestVersion", VERSION);
        m.put("manifestSha256", p.getManifestHash());
        m.put("algorithm", Ed25519Keys.ALGORITHM);
        m.put("signingKeyId", p.getSigningKeyId());
        m.put("timestampedAt", p.getTimestampAt() != null ? p.getTimestampAt().toString() : null);
        m.put("timestampAuthority", p.getTimestampAuthority());
        return m;
    }

    /** With the manifest itself, the signature and the public key, enough to check it without this server. */
    public Map<String, Object> fullView(Preservation p) {
        Map<String, Object> m = view(p);
        m.put("manifest", p.getManifest());
        m.put("signature", p.getSignature());
        m.put("publicKeyPem", keys.publicKey(p.getSigningKeyId()).map(k -> Ed25519Keys.pem("PUBLIC KEY", k.getEncoded())).orElse(null));
        m.put("timestampToken", p.getTimestampToken());
        return m;
    }

    public static Map<String, Object> verificationView(Verification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", v.status().name());
        m.put("message", v.message());
        m.put("preservationId", v.preservation().getPreservationId());
        m.put("caseNumber", v.caseNumber());
        m.put("preservedAt", v.preservation().getPreservedAt().toString());
        m.put("signingKeyId", v.preservation().getSigningKeyId());
        m.put("keyStatus", v.keyStatus());
        m.put("manifestSha256", v.preservation().getManifestHash());
        m.put("changedFields", v.changedFields());
        if (v.timestamp() != null) {
            Map<String, Object> ts = new LinkedHashMap<>();
            ts.put("status", v.timestamp().status().name());
            ts.put("time", v.timestamp().time() != null ? v.timestamp().time().toString() : null);
            ts.put("authority", v.timestamp().authority());
            ts.put("trusted", v.timestamp().trusted());
            ts.put("message", v.timestamp().message());
            m.put("timestamp", ts);
        } else {
            m.put("timestamp", null);
        }
        return m;
    }
}
