package com.forgeddetect.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.forgeddetect.service.CaseNumbers;
import com.forgeddetect.service.ModerationException;
import com.forgeddetect.signing.Ed25519Keys;
import com.forgeddetect.signing.ReportTimestampService;
import com.forgeddetect.signing.SigningKeyProvider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 28: signed evidence packages for an investigator under an approved legal request. A package
 * is built from the case's latest preservation (which must still verify) and the custody log as it
 * stands, signed and timestamped, and stored so the investigator can download exactly those bytes
 * for a limited time. Issuing and every download are custody events.
 */
@Service
public class EvidencePackageService {

    static final String PREFIX = "PKG";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final InvestigationService investigations;
    private final LegalRequestService legalRequests;
    private final CustodyLog custody;
    private final PreservationService preservations;
    private final PreservationRepository preservationRows;
    private final EvidencePackageRepository packages;
    private final SigningKeyProvider keys;
    private final ReportTimestampService timestamps;
    private final InvestigationProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EvidencePackageService(
            InvestigationService investigations,
            LegalRequestService legalRequests,
            CustodyLog custody,
            PreservationService preservations,
            PreservationRepository preservationRows,
            EvidencePackageRepository packages,
            SigningKeyProvider keys,
            ReportTimestampService timestamps,
            InvestigationProperties properties,
            Clock clock) {
        this.investigations = investigations;
        this.legalRequests = legalRequests;
        this.custody = custody;
        this.preservations = preservations;
        this.preservationRows = preservationRows;
        this.packages = packages;
        this.keys = keys;
        this.timestamps = timestamps;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ issue

    @Transactional
    public EvidencePackage issue(String caseNumber, String investigator) {
        LegalRequestService.Authorised auth = legalRequests.authorise(investigator, caseNumber);
        LegalRequest request = auth.request();
        Investigation inv = custody.lock(auth.investigation());
        List<Preservation> preserved = preservationRows.findByInvestigationIdOrderByPreservedAtAscIdAsc(inv.getId());
        if (preserved.isEmpty()) {
            throw new ModerationException(HttpStatus.CONFLICT, "not_preserved",
                    inv.getCaseNumber() + " has not been preserved yet. A package is built from a signed preservation; "
                            + "ask the platform's moderators to preserve the case first.");
        }
        CustodyChain.Result chain = custody.verify(inv);
        if (!chain.valid()) {
            throw new ModerationException(HttpStatus.CONFLICT, "custody_broken",
                    "The custody log of " + inv.getCaseNumber() + " fails verification, so no package can vouch for it: " + chain.problem());
        }
        Preservation p = preserved.get(preserved.size() - 1);
        PreservationService.Verification pv = preservations.verify(inv.getCaseNumber(), p.getPreservationId());
        if (pv.status() != PreservationService.Status.VALID) {
            throw new ModerationException(HttpStatus.CONFLICT, "evidence_tampered",
                    "Preservation " + p.getPreservationId() + " no longer verifies, so no package can be built from it: " + pv.message());
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant downloadUntil = min(now.plus(properties.getHandover().getDownloadWindow()), request.getExpiresAt());
        String packageId = CaseNumbers.generate(PREFIX, random, packages::existsByPackageId);
        custody.append(inv, investigator, InvestigationEventType.PACKAGE_ISSUED, null, null,
                "Evidence package " + packageId + " issued to " + investigator + " under " + request.getRequestNumber()
                        + " from preservation " + p.getPreservationId() + "; downloadable until " + downloadUntil + ".");

        List<InvestigationEvent> log = custody.events(inv);
        Map<String, Object> view = investigations.investigatorView(inv.getCaseNumber(), investigator, legalRequests.visibleCaseIds(investigator));
        String keyId = keys.activeKeyId();

        Map<String, Object> pkgInfo = new LinkedHashMap<>();
        pkgInfo.put("packageId", packageId);
        pkgInfo.put("format", PackageFormat.VERSION);
        pkgInfo.put("issuedAt", now.toString());
        pkgInfo.put("downloadExpiresAt", downloadUntil.toString());
        pkgInfo.put("preservationId", p.getPreservationId());
        pkgInfo.put("preservationManifestSha256", p.getManifestHash());
        pkgInfo.put("custodyLength", inv.getCustodyLength());
        pkgInfo.put("custodyHead", inv.getCustodyHead());
        pkgInfo.put("signingKeyId", keyId);
        Map<String, Object> report = new LinkedHashMap<>(view);
        report.put("package", pkgInfo);
        report.put("authorisation", LegalRequestService.summary(request, now));

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.txt", PackageFormat.utf8(readme(packageId, inv, request, p)));
        files.put("case/report.json", json(report));
        files.put("case/report.pdf", CaseReportRenderer.render(report));
        files.put("case/sightings.json", json(view.get("sightings")));
        files.put("custody/log.json", json(custodyLog(inv, log)));
        files.put("preservation/" + p.getPreservationId() + ".manifest.txt", PackageFormat.utf8(p.getManifest()));
        files.put("preservation/" + p.getPreservationId() + ".json", json(preservationMeta(p)));
        for (String id : new TreeSet<>(List.of(keyId, p.getSigningKeyId()))) {
            keys.publicKey(id).ifPresent(k -> files.put("keys/" + id + ".pem", PackageFormat.utf8(Ed25519Keys.pem("PUBLIC KEY", k.getEncoded()))));
        }

        Map<String, String> header = new LinkedHashMap<>();
        header.put("package_id", packageId);
        header.put("case_number", inv.getCaseNumber());
        header.put("case_title", inv.getTitle());
        header.put("legal_request", request.getRequestNumber());
        header.put("authority", request.getAuthority());
        header.put("order_reference", request.getOrderReference());
        header.put("investigator", investigator);
        header.put("approved_by", request.getDecidedBy());
        header.put("request_expires_at", request.getExpiresAt().toString());
        header.put("issued_at", now.toString());
        header.put("download_expires_at", downloadUntil.toString());
        header.put("preservation_id", p.getPreservationId());
        header.put("preservation_manifest_sha256", p.getManifestHash());
        header.put("custody_length", String.valueOf(inv.getCustodyLength()));
        header.put("custody_head", inv.getCustodyHead());
        header.put("signing_key_id", keyId);
        String manifest = PackageFormat.manifest(header, files);
        byte[] manifestBytes = PackageFormat.utf8(manifest);
        byte[] digest = Ed25519Keys.sha256(manifestBytes);
        SigningKeyProvider.SignatureResult signed = keys.sign(digest);
        ReportTimestampService.Outcome stamped = timestamps.stamp(signed.signature());
        ReportTimestampService.Stamp stamp = stamped.stamp();
        PackageFormat.Signature unsealed = new PackageFormat.Signature(signed.keyId(), PackageFormat.sha256Hex(manifestBytes),
                Base64.getEncoder().encodeToString(signed.signature()),
                stamp != null ? stamp.tokenBase64() : null, stamp != null ? stamp.time().toString() : null,
                stamp != null ? stamp.authority() : null, null);
        SigningKeyProvider.SignatureResult seal = keys.sign(Ed25519Keys.sha256(PackageFormat.utf8(PackageFormat.signatureBody(unsealed))));
        PackageFormat.Signature sig = new PackageFormat.Signature(unsealed.keyId(), unsealed.manifestSha256(), unsealed.signature(),
                unsealed.timestampToken(), unsealed.timestampTime(), unsealed.timestampAuthority(),
                Base64.getEncoder().encodeToString(seal.signature()));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PackageFormat.MANIFEST, manifestBytes);
        entries.put(PackageFormat.SIGNATURE, PackageFormat.utf8(PackageFormat.signatureFile(sig)));
        entries.putAll(files);
        byte[] zip = PackageFormat.zip(entries, now);

        return packages.save(new EvidencePackage(packageId, inv.getId(), request.getRequestNumber(), investigator, now, downloadUntil,
                p.getPreservationId(), inv.getCustodyLength(), inv.getCustodyHead(), PackageFormat.sha256Hex(manifestBytes),
                signed.keyId(), stamp != null ? stamp.time() : null, PackageFormat.sha256Hex(zip), zip));
    }

    // ------------------------------------------------------------------ download

    /** The stored bytes, if the package is this investigator's, still in its window and still authorised. */
    @Transactional
    public EvidencePackage download(String packageId, String investigator) {
        EvidencePackage pkg = own(packageId, investigator);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!now.isBefore(pkg.getDownloadExpiresAt())) {
            throw new ModerationException(HttpStatus.GONE, "package_expired",
                    pkg.getPackageId() + " could be downloaded until " + pkg.getDownloadExpiresAt() + ". Issue a new package.");
        }
        Investigation inv = investigations.findById(pkg.getInvestigationId());
        legalRequests.authorise(investigator, inv.getCaseNumber());
        custody.lock(inv);
        pkg.downloaded(now);
        packages.save(pkg);
        custody.append(inv, investigator, InvestigationEventType.PACKAGE_DOWNLOADED, null, null,
                "Evidence package " + pkg.getPackageId() + " downloaded by " + investigator + " (download " + pkg.getDownloads()
                        + ", ZIP SHA-256 " + pkg.getSha256() + ", " + pkg.getSize() + " bytes).");
        return pkg;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String investigator) {
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvidencePackage p : packages.findByInvestigatorOrderByIssuedAtDescIdDesc(investigator)) {
            Map<String, Object> v = view(p, now);
            v.put("caseNumber", investigations.findById(p.getInvestigationId()).getCaseNumber());
            out.add(v);
        }
        return out;
    }

    private EvidencePackage own(String packageId, String investigator) {
        String id = CaseNumbers.normalize(PREFIX, packageId);
        return (id == null ? Optional.<EvidencePackage>empty() : packages.findByPackageId(id))
                .filter(p -> p.getInvestigator().equals(investigator))
                .orElseThrow(() -> new ModerationException(HttpStatus.NOT_FOUND, "package_not_found", "No package " + packageId + " issued to you."));
    }

    // ------------------------------------------------------------------ verify

    /**
     * Anyone holding a package can check it here: the same checks as the offline script, plus the
     * RFC 3161 token against the configured TSA certificates and, for a package this server issued,
     * that the ZIP is byte for byte the one it issued.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> verifyUpload(byte[] zip) {
        PackageVerifier.Result r = PackageVerifier.verify(zip, this::publicKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "evidence_package");
        List<Map<String, Object>> checks = new ArrayList<>(r.checks().stream()
                .map(c -> check(c.name(), c.ok(), c.detail())).toList());
        String status = r.status().name();
        String message = r.message();
        if (r.status() != PackageVerifier.Status.INVALID) {
            PackageFormat.Signature sig = r.signature();
            if (sig.timestampToken() != null && r.signatureBytes() != null) {
                ReportTimestampService.Check ts = timestamps.verify(sig.timestampToken(), r.signatureBytes());
                boolean ok = ts.status() == ReportTimestampService.CheckStatus.VERIFIED;
                checks.add(check("timestamp", ok, ok ? "RFC 3161 timestamp " + ts.time() + " by " + ts.authority()
                        + (ts.trusted() ? " (trusted TSA)." : " (TSA certificate not in the trusted list).") : ts.message()));
                if (!ok && "VALID".equals(status)) {
                    status = "TAMPERED";
                    message = ts.message();
                }
            }
            Optional<EvidencePackage> issued = Optional.ofNullable(r.manifest().get("package_id")).flatMap(packages::findByPackageId);
            if (issued.isPresent()) {
                boolean same = issued.get().getSha256().equals(PackageFormat.sha256Hex(zip));
                checks.add(check("issued", same, same ? "Byte for byte the package this server issued on " + issued.get().getIssuedAt() + "."
                        : "This server issued " + issued.get().getPackageId() + " with a different ZIP SHA-256; this copy was altered."));
                if (!same && "VALID".equals(status)) {
                    status = "TAMPERED";
                    message = "The ZIP differs from the package this server issued.";
                }
            }
            out.put("packageId", r.manifest().get("package_id"));
            out.put("caseNumber", r.manifest().get("case_number"));
            out.put("legalRequest", r.manifest().get("legal_request"));
            out.put("authority", r.manifest().get("authority"));
            out.put("investigator", r.manifest().get("investigator"));
            out.put("issuedAt", r.manifest().get("issued_at"));
            out.put("signingKeyId", sig.keyId());
        }
        out.put("status", status);
        out.put("message", message);
        out.put("sha256", PackageFormat.sha256Hex(zip));
        out.put("checks", checks);
        return out;
    }

    private Optional<PublicKey> publicKey(String keyId) {
        return keys.publicKey(keyId);
    }

    /** Any ZIP is checked as a package (media uploads are never ZIPs); anything else goes to provenance verification. */
    public static boolean looksLikePackage(byte[] data) {
        return data != null && data.length > 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

    // ------------------------------------------------------------------ contents

    private static Map<String, Object> custodyLog(Investigation inv, List<InvestigationEvent> log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseNumber", inv.getCaseNumber());
        m.put("version", CustodyChain.VERSION);
        m.put("algorithm", "hash = SHA-256(prevHash + \"\\n\" + canonical event), canonical = " + CustodyChain.VERSION
                + " then case, seq, occurred_at, actor, type, analysis_id, sighting_id, detail as key=value lines");
        m.put("genesis", CustodyChain.GENESIS);
        m.put("length", inv.getCustodyLength());
        m.put("head", inv.getCustodyHead());
        m.put("events", log.stream().map(CustodyLog::eventView).toList());
        return m;
    }

    private static Map<String, Object> preservationMeta(Preservation p) {
        Map<String, Object> m = new LinkedHashMap<>(PreservationService.view(p));
        m.put("signature", p.getSignature());
        m.put("timestampToken", p.getTimestampToken());
        return m;
    }

    private static String readme(String packageId, Investigation inv, LegalRequest request, Preservation p) {
        return """
                Evidence package %s
                Case %s: %s
                Issued under legal request %s (%s, reference %s) to %s.

                What is in it
                  PACKAGE-MANIFEST.txt     every other file with its SHA-256 and size (this is what is signed)
                  PACKAGE-SIGNATURE.txt    Ed25519 signature over SHA-256(PACKAGE-MANIFEST.txt), the RFC 3161
                                           timestamp over that signature (if a TSA is configured) and a seal
                  case/report.json         the case as the investigator is authorised to see it
                  case/report.pdf          the same, for reading
                  case/sightings.json      where partner platforms reported the media (account pseudonyms only)
                  custody/log.json         the hash-chained custody log of the case up to this package
                  preservation/%s.*   the signed preservation manifest the package is built from
                  keys/*.pem               the public keys used (compare them with the published ones)

                No media is included. The platform hosting a post keeps the file under its own legal hold; the
                content_sha256 values let you prove a copy you obtain from it is the file that was analysed
                (ADR 0006 says exactly how to compute them).

                How to check it, offline
                  1. Get the published keys: GET /api/verify-report/keys (save as keys.json).
                  2. python3 verify_evidence_package.py %s.zip --keys keys.json
                     (tools/verify_evidence_package.py in the project repository; Python 3.9+, no packages needed)
                  It checks the signature and seal, every file's SHA-256, every link of the custody chain and
                  the preservation signature. It exits 0 only if everything verifies.
                  3. Optional: the timestamp token, with the TSA's certificate:
                     openssl ts -verify -token_in -in token.tsr -data signature.bin -CAfile tsa.pem
                     (the script writes token.tsr and signature.bin with --extract-timestamp DIR)
                Or upload the ZIP to /verify on the platform's site.
                """.formatted(packageId, inv.getCaseNumber(), inv.getTitle(), request.getRequestNumber(), request.getAuthority(),
                request.getOrderReference(), request.getInvestigator(), p.getPreservationId(), packageId);
    }

    private static byte[] json(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not write package JSON: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    public static Map<String, Object> view(EvidencePackage p, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packageId", p.getPackageId());
        m.put("requestNumber", p.getRequestNumber());
        m.put("investigator", p.getInvestigator());
        m.put("issuedAt", p.getIssuedAt().toString());
        m.put("downloadExpiresAt", p.getDownloadExpiresAt().toString());
        m.put("downloadable", now.isBefore(p.getDownloadExpiresAt()));
        m.put("preservationId", p.getPreservationId());
        m.put("custodyLength", p.getCustodyLength());
        m.put("custodyHead", p.getCustodyHead());
        m.put("manifestSha256", p.getManifestSha256());
        m.put("signingKeyId", p.getSigningKeyId());
        m.put("timestampedAt", p.getTimestampAt() != null ? p.getTimestampAt().toString() : null);
        m.put("sha256", p.getSha256());
        m.put("size", p.getSize());
        m.put("downloads", p.getDownloads());
        m.put("lastDownloadedAt", p.getLastDownloadedAt() != null ? p.getLastDownloadedAt().toString() : null);
        return m;
    }
}
