package com.forgeddetect.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.signing.Ed25519Keys;
import com.forgeddetect.signing.ReportTimestampService;
import com.forgeddetect.signing.SigningKeyProvider;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 20: signs a canonical snapshot of each evidentiary report and verifies it later.
 *
 * <p>The snapshot is a fixed, ordered list of the fields the report asserts (scores, decision,
 * SLA timestamps, provenance ID...). Its SHA-256 is signed with Ed25519. The snapshot, hash and
 * signature are stored on the analysis row, so verification can tell a valid report from one whose
 * record changed afterwards, and name the fields that changed.
 *
 * <p>Phase 24: the key comes from a {@link SigningKeyProvider} (a dev keyring on disk, or a KMS such
 * as Vault Transit where the private key never leaves the device). Keys rotate; retired keys stay
 * in the keyring so the reports they signed still verify. When a Time-Stamping Authority is
 * configured, each signature also gets an RFC 3161 timestamp token.
 */
@Service
public class ReportSigningService {

    public static final String ALGORITHM = Ed25519Keys.ALGORITHM;
    static final String CANONICAL_V1 = "fmd-evidentiary-report/v1";
    /** v2 adds digests of the video frame timeline and the C2PA summary, and the senior-escalation record. */
    static final String CANONICAL_VERSION = "fmd-evidentiary-report/v2";
    private static final Pattern REPORT_ID = Pattern.compile("^RPT-(\\d+)-\\d{14}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Status { VALID, TAMPERED, NOT_FOUND }

    /** Timestamp on a newly signed report: {@code STAMPED}, {@code UNAVAILABLE} (TSA failed) or {@code DISABLED}. */
    public record Timestamp(String status, Instant time, String authority, String error) {}

    public record Signed(
            String reportId, Instant signedAt, String canonical, String contentHash, String signature, String keyId,
            Timestamp timestamp) {}

    /**
     * {@code changedFields} lists names only: values can include complaint references, and this endpoint is public.
     * {@code keyStatus} is ACTIVE, RETIRED or UNKNOWN; {@code timestamp} is null when the report carries none.
     */
    public record Verification(
            Status status, String message, String reportId, Long analysisId, Instant signedAt, String keyId,
            String contentHash, List<String> changedFields, String keyStatus, ReportTimestampService.Check timestamp) {}

    private static final Logger log = LoggerFactory.getLogger(ReportSigningService.class);

    private final AnalysisResultRepository results;
    private final SigningKeyProvider keys;
    private final ReportTimestampService timestamps;

    public ReportSigningService(
            AnalysisResultRepository results, SigningKeyProvider keys, ReportTimestampService timestamps) {
        this.results = results;
        this.keys = keys;
        this.timestamps = timestamps;
        log.info("Report signing key {} ({}, provider {}{}); RFC 3161 timestamps {}", keys.activeKeyId(), ALGORITHM,
                keys.kind(), keys.production() ? "" : " - development only",
                timestamps.enabled() ? "from " + timestamps.url() : "disabled");
    }

    public String keyId() {
        return keys.activeKeyId();
    }

    public SigningKeyProvider provider() {
        return keys;
    }

    public boolean timestampsEnabled() {
        return timestamps.enabled();
    }

    public String publicKeyPem() {
        String active = keys.activeKeyId();
        return keys.publicKey(active)
                .map(k -> Ed25519Keys.pem("PUBLIC KEY", k.getEncoded()))
                .orElseThrow(() -> new IllegalStateException("Active key " + active + " has no public key"));
    }

    /** Signs the item as it stands now and stores the snapshot + signature (+ timestamp) on it. Does not save. */
    public Signed sign(AnalysisResult item, String reportId, Instant signedAt) {
        String canonical = canonical(item, reportId, signedAt);
        byte[] digest = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        SigningKeyProvider.SignatureResult result = keys.sign(digest);
        String signature = Base64.getEncoder().encodeToString(result.signature());
        String hash = HexFormat.of().formatHex(digest);
        item.setReportId(reportId);
        item.setReportSignedAt(signedAt);
        item.setReportCanonical(canonical);
        item.setReportContentHash(hash);
        item.setReportSignature(signature);
        item.setReportSigningKeyId(result.keyId());

        ReportTimestampService.Outcome stamped = timestamps.stamp(result.signature());
        Timestamp ts;
        if (stamped.stamp() != null) {
            item.setReportTimestampToken(stamped.stamp().tokenBase64());
            item.setReportTimestampAt(stamped.stamp().time());
            item.setReportTimestampAuthority(truncate(stamped.stamp().authority(), 255));
            ts = new Timestamp("STAMPED", stamped.stamp().time(), stamped.stamp().authority(), null);
        } else {
            item.setReportTimestampToken(null);
            item.setReportTimestampAt(null);
            item.setReportTimestampAuthority(null);
            ts = new Timestamp(timestamps.enabled() ? "UNAVAILABLE" : "DISABLED", null, null, stamped.error());
        }
        return new Signed(reportId, signedAt, canonical, hash, signature, result.keyId(), ts);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** @param id a report ID ({@code RPT-12-20260927101500}) or a bare analysis ID */
    @Transactional(readOnly = true)
    public Verification verify(String id) {
        String requested = id == null ? "" : id.trim();
        Long analysisId;
        String expectedReportId = null;
        Matcher m = REPORT_ID.matcher(requested);
        if (m.matches()) {
            analysisId = Long.parseLong(m.group(1));
            expectedReportId = requested;
        } else if (requested.matches("^\\d{1,18}$")) {
            analysisId = Long.parseLong(requested);
        } else {
            return notFound("Not a report ID. Expected the form RPT-<analysis>-<yyyyMMddHHmmss>.", null);
        }

        Optional<AnalysisResult> found = results.findById(analysisId);
        if (found.isEmpty()) {
            return notFound("No analysis #" + analysisId + " exists.", analysisId);
        }
        AnalysisResult item = found.get();
        if (item.getReportSignature() == null || item.getReportCanonical() == null) {
            return notFound("No signed report is on record for analysis #" + analysisId + ".", analysisId);
        }
        if (expectedReportId != null && !expectedReportId.equals(item.getReportId())) {
            return notFound("Report " + expectedReportId + " is not the latest signed report for analysis #" + analysisId
                    + " (" + item.getReportId() + " superseded it), so it can't be checked against the record.", analysisId);
        }

        String signedWith = item.getReportSigningKeyId();
        Optional<PublicKey> key = keys.publicKey(signedWith);
        if (key.isEmpty()) {
            return tampered(item, "Signed with key " + signedWith + ", which is not in this server's keyring (active key "
                    + keys.activeKeyId() + "). The signature can't be trusted.", List.of("signing_key"), "UNKNOWN", null);
        }
        String keyStatus = signedWith.equals(keys.activeKeyId()) ? "ACTIVE" : "RETIRED";
        byte[] storedDigest = sha256(item.getReportCanonical().getBytes(StandardCharsets.UTF_8));
        byte[] signature = decodeSignature(item.getReportSignature());
        if (!HexFormat.of().formatHex(storedDigest).equals(item.getReportContentHash()) || signature == null
                || !Ed25519Keys.verify(key.get(), storedDigest, signature)) {
            return tampered(item, "The stored signature does not match the stored snapshot: the signature record itself was altered.",
                    List.of("signature"), keyStatus, null);
        }
        ReportTimestampService.Check ts = item.getReportTimestampToken() == null
                ? null : timestamps.verify(item.getReportTimestampToken(), signature);
        if (ts != null && ts.status() != ReportTimestampService.CheckStatus.VERIFIED) {
            return tampered(item, "The signature is genuine, but its RFC 3161 timestamp does not check out: " + ts.message(),
                    List.of("timestamp"), keyStatus, ts);
        }
        String current = canonical(item, item.getReportId(), item.getReportSignedAt(), versionOf(item.getReportCanonical()));
        List<String> changed = diff(item.getReportCanonical(), current);
        if (!changed.isEmpty()) {
            return tampered(item, "The signature is genuine, but the record no longer matches what was signed. Changed since signing: "
                    + String.join(", ", changed) + ". If the change was a legitimate update, issue a new report.", changed, keyStatus, ts);
        }
        String keyNote = "RETIRED".equals(keyStatus)
                ? " (a retired key: rotated out since, and still trusted for the reports it signed)" : "";
        String tsNote = ts != null ? " Timestamped " + ts.time() + " by " + ts.authority() + "." : "";
        return new Verification(Status.VALID,
                "Signature verified with " + ALGORITHM + " key " + signedWith + keyNote
                        + ". The record matches the signed report exactly." + tsNote,
                item.getReportId(), item.getId(), item.getReportSignedAt(), signedWith, item.getReportContentHash(), List.of(),
                keyStatus, ts);
    }

    private static byte[] decodeSignature(String b64) {
        try {
            return b64 == null ? null : Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ canonical form

    /** Fixed field order, one {@code key=value} per line, UTF-8. Changing this means a new version string. */
    static String canonical(AnalysisResult item, String reportId, Instant signedAt) {
        return canonical(item, reportId, signedAt, CANONICAL_VERSION);
    }

    /** Rebuilds the snapshot in the version a report was signed with, so older reports keep verifying. */
    static String canonical(AnalysisResult item, String reportId, Instant signedAt, String version) {
        Map<String, String> f = fields(item, reportId, signedAt);
        if (!CANONICAL_V1.equals(version)) {
            putIfSet(f, "frame_timeline_sha256", item.isVideo() ? digest(item.getFrameTimeline()) : null);
            putIfSet(f, "c2pa_manifest_sha256", digest(item.getC2paManifest()));
            putIfSet(f, "senior_escalated_at", str(item.getSeniorEscalatedAt()));
            putIfSet(f, "senior_resolved_by", item.getSeniorResolvedBy());
        }
        StringBuilder sb = new StringBuilder(version).append('\n');
        f.forEach((k, v) -> sb.append(k).append('=').append(escape(v)).append('\n'));
        return sb.toString();
    }

    private static String versionOf(String canonical) {
        int nl = canonical.indexOf('\n');
        return nl > 0 ? canonical.substring(0, nl) : canonical;
    }

    private static String digest(String value) {
        return value == null || value.isBlank()
                ? null : HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> fields(AnalysisResult item, String reportId, Instant signedAt) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("report_id", reportId);
        f.put("signed_at", str(signedAt));
        f.put("analysis_id", str(item.getId()));
        f.put("provenance_id", item.getProvenanceId());
        f.put("analysed_at", str(item.getCreatedAt()));
        f.put("decision", item.getDecision() != null ? item.getDecision().name() : null);
        f.put("forgery_score", String.format(Locale.ROOT, "%.6f", item.getForgeryScore()));
        f.put("explicit_score", String.format(Locale.ROOT, "%.6f", item.getExplicitScore()));
        f.put("generation_family", item.getGenerationFamily());
        f.put("phash", item.getPhash());
        f.put("content_hash", item.getContentHash());
        f.put("source_provenance_id", item.getSourceProvenanceId());
        f.put("jurisdiction", item.getJurisdiction());
        f.put("complaint_type", item.getComplaintType() != null ? item.getComplaintType().name() : null);
        f.put("complaint_reference", item.getComplaintReference());
        f.put("sla_rule", item.getSlaRuleLabel());
        f.put("complaint_received_at", str(item.getComplaintReceivedAt()));
        f.put("sla_deadline", str(item.getSlaDeadline()));
        f.put("resolved_at", str(item.getResolvedAt()));
        // Phase 23: only emitted when set, so reports signed before these fields existed still verify
        putIfSet(f, "media_type", item.isVideo() ? "VIDEO" : null);
        putIfSet(f, "frames_sampled", item.isVideo() ? framesSampled(item.getFrameTimeline()) : null);
        putIfSet(f, "decided_by", item.getDecidedBy());
        putIfSet(f, "appeal_status", item.getAppealStatus());
        putIfSet(f, "appeal_decided_at", str(item.getAppealDecidedAt()));
        putIfSet(f, "c2pa_status", jsonField(item.getC2paManifest(), "status"));
        return f;
    }

    private static void putIfSet(Map<String, String> f, String key, String value) {
        if (value != null) {
            f.put(key, value);
        }
    }

    private static String framesSampled(String timeline) {
        JsonNode node = readJson(timeline);
        return node != null && node.path("frames").isArray() ? String.valueOf(node.path("frames").size()) : null;
    }

    private static String jsonField(String json, String field) {
        JsonNode node = readJson(json);
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static List<String> diff(String signed, String current) {
        Map<String, String> a = parse(signed);
        Map<String, String> b = parse(current);
        List<String> changed = new ArrayList<>();
        for (String key : a.keySet()) {
            if (!Objects.equals(a.get(key), b.get(key))) {
                changed.add(key);
            }
        }
        for (String key : b.keySet()) {
            if (!a.containsKey(key)) {
                changed.add(key);
            }
        }
        return changed;
    }

    private static Map<String, String> parse(String canonical) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = canonical.split("\n");
        for (int i = 1; i < lines.length; i++) {
            int eq = lines[i].indexOf('=');
            if (eq > 0) {
                out.put(lines[i].substring(0, eq), lines[i].substring(eq + 1));
            }
        }
        return out;
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    // ------------------------------------------------------------------ results

    private Verification tampered(
            AnalysisResult item, String message, List<String> changed, String keyStatus, ReportTimestampService.Check ts) {
        return new Verification(Status.TAMPERED, message, item.getReportId(), item.getId(), item.getReportSignedAt(),
                item.getReportSigningKeyId(), item.getReportContentHash(), changed, keyStatus, ts);
    }

    private Verification notFound(String message, Long analysisId) {
        return new Verification(Status.NOT_FOUND, message, null, analysisId, null, keys.activeKeyId(), null, List.of(), null, null);
    }

    static byte[] sha256(byte[] data) {
        return Ed25519Keys.sha256(data);
    }
}
