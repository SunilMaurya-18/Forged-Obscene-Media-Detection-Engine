package com.forgeddetect.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.signing.Ed25519Keys;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 28: checks an evidence package from its bytes and a public-key lookup alone, as the offline
 * script does: the manifest signature and seal, every file's SHA-256 and size, the custody chain in
 * the package against the head the manifest commits to, and the preservation inside it.
 */
final class PackageVerifier {

    enum Status { VALID, TAMPERED, INVALID }

    record Check(String name, boolean ok, String detail) {}

    /** {@code signature}: the raw Ed25519 signature bytes, for checking the timestamp token against. */
    record Result(Status status, String message, Map<String, String> manifest, PackageFormat.Signature signature,
            byte[] signatureBytes, List<Check> checks) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private PackageVerifier() {}

    static Result verify(byte[] zip, Function<String, Optional<PublicKey>> keys) {
        List<Check> checks = new ArrayList<>();
        Map<String, byte[]> files;
        Map<String, String> manifest;
        PackageFormat.Signature sig;
        try {
            files = PackageFormat.unzip(zip);
            byte[] manifestBytes = files.get(PackageFormat.MANIFEST);
            byte[] sigBytes = files.get(PackageFormat.SIGNATURE);
            if (manifestBytes == null || sigBytes == null) {
                return invalid("This is not an evidence package: it has no " + PackageFormat.MANIFEST + " and " + PackageFormat.SIGNATURE + ".", checks);
            }
            manifest = PackageFormat.parse(new String(manifestBytes, StandardCharsets.UTF_8), PackageFormat.VERSION);
            sig = PackageFormat.parseSignature(new String(sigBytes, StandardCharsets.UTF_8));
            checks.add(new Check("structure", true, files.size() + " files, manifest " + PackageFormat.VERSION));

            // 1. Signature over the manifest, and the seal over the signature block
            byte[] digest = Ed25519Keys.sha256(manifestBytes);
            byte[] signature = decode(sig.signature());
            byte[] seal = decode(sig.seal());
            Optional<PublicKey> key = Ed25519Keys.isKeyId(sig.keyId()) ? keys.apply(sig.keyId()) : Optional.empty();
            if (key.isEmpty()) {
                checks.add(new Check("signature", false, "Signed with key " + sig.keyId() + ", which is not a published signing key."));
                return done(checks, manifest, sig, signature);
            }
            if (!HexFormat.of().formatHex(digest).equals(sig.manifestSha256()) || signature == null
                    || !Ed25519Keys.verify(key.get(), digest, signature)) {
                checks.add(new Check("signature", false, "The Ed25519 signature does not match " + PackageFormat.MANIFEST + "."));
                return done(checks, manifest, sig, signature);
            }
            if (seal == null || !Ed25519Keys.verify(key.get(),
                    Ed25519Keys.sha256(PackageFormat.utf8(PackageFormat.signatureBody(sig))), seal)) {
                checks.add(new Check("signature", false, "The seal over " + PackageFormat.SIGNATURE + " does not verify: its timestamp or signature lines were changed."));
                return done(checks, manifest, sig, signature);
            }
            if (!sig.keyId().equals(manifest.get("signing_key_id"))) {
                checks.add(new Check("signature", false, "The manifest names key " + manifest.get("signing_key_id") + " but was signed with " + sig.keyId() + "."));
                return done(checks, manifest, sig, signature);
            }
            checks.add(new Check("signature", true, "Ed25519 signature and seal verified with key " + sig.keyId() + "."));

            // 2. Every file listed, present and unchanged; nothing extra
            checks.add(files(files, manifest));

            // 3 and 4. Custody chain and the preservation it contains
            checks.addAll(custodyAndPreservation(files, manifest, keys));
            return done(checks, manifest, sig, signature);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage(), checks);
        }
    }

    private static Check files(Map<String, byte[]> files, Map<String, String> manifest) {
        int count;
        try {
            count = Integer.parseInt(manifest.getOrDefault("file_count", ""));
        } catch (NumberFormatException ex) {
            return new Check("files", false, "The manifest has no file count.");
        }
        Set<String> listed = new HashSet<>();
        for (int i = 1; i <= count; i++) {
            String path = manifest.get("file." + i + ".path");
            byte[] data = path != null ? files.get(path) : null;
            if (path == null || !listed.add(path)) {
                return new Check("files", false, "Entry " + i + " of the manifest is missing or repeated.");
            }
            if (data == null) {
                return new Check("files", false, path + " is listed in the manifest but missing from the package.");
            }
            if (!PackageFormat.sha256Hex(data).equals(manifest.get("file." + i + ".sha256"))
                    || !String.valueOf(data.length).equals(manifest.get("file." + i + ".size"))) {
                return new Check("files", false, path + " was changed: its SHA-256 or size no longer matches the manifest.");
            }
        }
        for (String name : files.keySet()) {
            if (!listed.contains(name) && !name.equals(PackageFormat.MANIFEST) && !name.equals(PackageFormat.SIGNATURE)) {
                return new Check("files", false, name + " was added to the package; the manifest does not list it.");
            }
        }
        return new Check("files", true, "All " + count + " listed files match their SHA-256 and size.");
    }

    private static List<Check> custodyAndPreservation(Map<String, byte[]> files, Map<String, String> manifest,
            Function<String, Optional<PublicKey>> keys) {
        List<Check> out = new ArrayList<>();
        String caseNumber = manifest.get("case_number");
        String pid = manifest.get("preservation_id");
        byte[] log = files.get("custody/log.json");
        byte[] pMeta = files.get("preservation/" + pid + ".json");
        byte[] pManifest = files.get("preservation/" + pid + ".manifest.txt");
        if (log == null || pMeta == null || pManifest == null) {
            out.add(new Check("custody", false, "The package is missing its custody log or preservation files."));
            return out;
        }

        // Preservation: its own signature, and the custody head it committed to
        Map<String, String> preserved;
        try {
            JsonNode meta = JSON.readTree(pMeta);
            byte[] digest = Ed25519Keys.sha256(pManifest);
            String keyId = meta.path("signingKeyId").asText();
            Optional<PublicKey> key = Ed25519Keys.isKeyId(keyId) ? keys.apply(keyId) : Optional.empty();
            byte[] signature = decode(meta.path("signature").asText(null));
            preserved = PreservationService.parse(new String(pManifest, StandardCharsets.UTF_8));
            if (key.isEmpty()) {
                out.add(new Check("preservation", false, "Preservation " + pid + " was signed with key " + keyId + ", which is not a published signing key."));
            } else if (!HexFormat.of().formatHex(digest).equals(meta.path("manifestSha256").asText())
                    || !HexFormat.of().formatHex(digest).equals(manifest.get("preservation_manifest_sha256"))
                    || signature == null || !Ed25519Keys.verify(key.get(), digest, signature)) {
                out.add(new Check("preservation", false, "The signature of preservation " + pid + " does not match its manifest."));
            } else if (!pid.equals(preserved.get("preservation_id")) || !caseNumber.equals(preserved.get("case_number"))) {
                out.add(new Check("preservation", false, "The preserved manifest is for a different preservation or case."));
            } else {
                out.add(new Check("preservation", true, "Preservation " + pid + " signature verified with key " + keyId + "."));
            }
        } catch (Exception ex) {
            out.add(new Check("preservation", false, "The preservation files can't be read: " + ex.getMessage()));
            return out;
        }

        // Custody chain: every link, the head the manifest names, and the head the preservation signed
        try {
            JsonNode root = JSON.readTree(log);
            if (!caseNumber.equals(root.path("caseNumber").asText())) {
                out.add(new Check("custody", false, "The custody log is for " + root.path("caseNumber").asText() + ", not " + caseNumber + "."));
                return out;
            }
            List<CustodyChain.Entry> entries = new ArrayList<>();
            for (JsonNode e : root.path("events")) {
                entries.add(new CustodyChain.Entry(null, e.path("seq").isNumber() ? e.path("seq").asInt() : null,
                        e.path("occurredAt").asText(null), text(e, "actor"), text(e, "type"),
                        e.path("analysisId").isNumber() ? e.path("analysisId").asLong() : null,
                        e.path("sightingId").isNumber() ? e.path("sightingId").asLong() : null,
                        text(e, "detail"), text(e, "prevHash"), text(e, "hash")));
            }
            int length = Integer.parseInt(manifest.getOrDefault("custody_length", "-1"));
            int anchorLength = Integer.parseInt(preserved.getOrDefault("custody_length", "-1"));
            CustodyChain.Result r = CustodyChain.verify(caseNumber, entries, length, manifest.get("custody_head"),
                    List.of(new CustodyChain.Anchor("Preservation " + pid, anchorLength, preserved.get("custody_head"))));
            out.add(r.valid()
                    ? new Check("custody", true, "All " + r.length() + " custody events chain correctly to the head in the manifest, "
                            + "including the head preservation " + pid + " signed at event " + anchorLength + ".")
                    : new Check("custody", false, r.problem()));
        } catch (Exception ex) {
            out.add(new Check("custody", false, "The custody log can't be read: " + ex.getMessage()));
        }
        return out;
    }

    private static Result done(List<Check> checks, Map<String, String> manifest, PackageFormat.Signature sig, byte[] signature) {
        Check failed = checks.stream().filter(c -> !c.ok()).findFirst().orElse(null);
        return failed == null
                ? new Result(Status.VALID, "The package is intact: signature, every file, the custody chain and the preservation all verify.",
                        manifest, sig, signature, checks)
                : new Result(Status.TAMPERED, failed.detail(), manifest, sig, signature, checks);
    }

    private static Result invalid(String message, List<Check> checks) {
        return new Result(Status.INVALID, message, Map.of(), null, null, checks);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static byte[] decode(String b64) {
        try {
            return b64 == null ? null : Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static Map<String, Object> view(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name());
        m.put("message", r.message());
        m.put("checks", r.checks().stream().map(c -> Map.of("name", c.name(), "ok", c.ok(), "detail", c.detail())).toList());
        return m;
    }
}
