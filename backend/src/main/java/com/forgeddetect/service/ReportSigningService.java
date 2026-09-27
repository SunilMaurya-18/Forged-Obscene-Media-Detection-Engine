package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.repository.AnalysisResultRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
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
import org.springframework.beans.factory.annotation.Value;
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
 * <p>Scope: the key is a <b>development</b> keypair generated on first start and kept in a local
 * directory. That demonstrates the mechanism only. Legal admissibility would need the private key in
 * a KMS/HSM and an RFC 3161 timestamp from a trusted third-party authority, not a self-signed dev key.
 */
@Service
public class ReportSigningService {

    public static final String ALGORITHM = "Ed25519";
    static final String CANONICAL_VERSION = "fmd-evidentiary-report/v1";
    private static final String PRIVATE_FILE = "report-signing-ed25519.key";
    private static final String PUBLIC_FILE = "report-signing-ed25519.pub";
    private static final Pattern REPORT_ID = Pattern.compile("^RPT-(\\d+)-\\d{14}$");

    public enum Status { VALID, TAMPERED, NOT_FOUND }

    public record Signed(String reportId, Instant signedAt, String canonical, String contentHash, String signature, String keyId) {}

    /** {@code changedFields} lists names only: values can include complaint references, and this endpoint is public. */
    public record Verification(
            Status status, String message, String reportId, Long analysisId, Instant signedAt, String keyId,
            String contentHash, List<String> changedFields) {}

    private static final Logger log = LoggerFactory.getLogger(ReportSigningService.class);

    private final AnalysisResultRepository results;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    private final Path keyDir;

    public ReportSigningService(
            AnalysisResultRepository results, @Value("${report.signing.key-dir:./data/signing-keys}") String keyDir) {
        this.results = results;
        this.keyDir = Path.of(keyDir).toAbsolutePath().normalize();
        KeyPair pair = loadOrCreate(this.keyDir);
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.keyId = HexFormat.of().formatHex(sha256(publicKey.getEncoded())).substring(0, 16);
        log.info("Report signing key {} ({}, dev keypair in {})", keyId, ALGORITHM, this.keyDir);
    }

    public String keyId() {
        return keyId;
    }

    public String publicKeyPem() {
        return pem("PUBLIC KEY", publicKey.getEncoded());
    }

    /** Signs the item as it stands now and stores the snapshot + signature on it. Does not save. */
    public Signed sign(AnalysisResult item, String reportId, Instant signedAt) {
        String canonical = canonical(item, reportId, signedAt);
        byte[] digest = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        String signature;
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(privateKey);
            signer.update(digest);
            signature = Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not sign report: " + ex.getMessage(), ex);
        }
        String hash = HexFormat.of().formatHex(digest);
        item.setReportId(reportId);
        item.setReportSignedAt(signedAt);
        item.setReportCanonical(canonical);
        item.setReportContentHash(hash);
        item.setReportSignature(signature);
        item.setReportSigningKeyId(keyId);
        return new Signed(reportId, signedAt, canonical, hash, signature, keyId);
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

        if (!keyId.equals(item.getReportSigningKeyId())) {
            return tampered(item, "Signed with key " + item.getReportSigningKeyId() + ", which is not this server's key ("
                    + keyId + "). The signature can't be trusted.", List.of("signing_key"));
        }
        byte[] storedDigest = sha256(item.getReportCanonical().getBytes(StandardCharsets.UTF_8));
        if (!HexFormat.of().formatHex(storedDigest).equals(item.getReportContentHash()) || !signatureValid(storedDigest, item.getReportSignature())) {
            return tampered(item, "The stored signature does not match the stored snapshot: the signature record itself was altered.",
                    List.of("signature"));
        }
        String current = canonical(item, item.getReportId(), item.getReportSignedAt());
        List<String> changed = diff(item.getReportCanonical(), current);
        if (!changed.isEmpty()) {
            return tampered(item, "The signature is genuine, but the record no longer matches what was signed. Changed since signing: "
                    + String.join(", ", changed) + ". If the change was a legitimate update, issue a new report.", changed);
        }
        return new Verification(Status.VALID,
                "Signature verified with " + ALGORITHM + " key " + keyId + ". The record matches the signed report exactly.",
                item.getReportId(), item.getId(), item.getReportSignedAt(), keyId, item.getReportContentHash(), List.of());
    }

    // ------------------------------------------------------------------ canonical form

    /** Fixed field order, one {@code key=value} per line, UTF-8. Changing this means a new version string. */
    static String canonical(AnalysisResult item, String reportId, Instant signedAt) {
        Map<String, String> f = fields(item, reportId, signedAt);
        StringBuilder sb = new StringBuilder(CANONICAL_VERSION).append('\n');
        f.forEach((k, v) -> sb.append(k).append('=').append(escape(v)).append('\n'));
        return sb.toString();
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
        return f;
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

    // ------------------------------------------------------------------ crypto + key storage

    private boolean signatureValid(byte[] digest, String signatureB64) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(digest);
            return verifier.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }

    private Verification tampered(AnalysisResult item, String message, List<String> changed) {
        return new Verification(Status.TAMPERED, message, item.getReportId(), item.getId(), item.getReportSignedAt(),
                item.getReportSigningKeyId(), item.getReportContentHash(), changed);
    }

    private Verification notFound(String message, Long analysisId) {
        return new Verification(Status.NOT_FOUND, message, null, analysisId, null, keyId, null, List.of());
    }

    private static KeyPair loadOrCreate(Path dir) {
        Path priv = dir.resolve(PRIVATE_FILE);
        Path pub = dir.resolve(PUBLIC_FILE);
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            if (Files.exists(priv) && Files.exists(pub)) {
                return new KeyPair(
                        factory.generatePublic(new X509EncodedKeySpec(unpem(Files.readString(pub)))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(unpem(Files.readString(priv)))));
            }
            Files.createDirectories(dir);
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            Files.writeString(priv, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(pub, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            try {
                Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: rely on the directory's ACLs
            }
            log.warn("Generated a new DEV report-signing keypair in {}. Production must use a KMS/HSM.", dir);
            return pair;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Report signing key unavailable in " + dir + ": " + ex.getMessage(), ex);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] unpem(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", ""));
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
