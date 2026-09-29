package com.forgeddetect.investigation;

import com.forgeddetect.signing.Ed25519Keys;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Phase 28: the layout of an evidence package. {@code PACKAGE-MANIFEST.txt} lists every other file
 * with its SHA-256 and size; {@code PACKAGE-SIGNATURE.txt} holds the Ed25519 signature over the
 * manifest's SHA-256, the RFC 3161 token over that signature, and a seal (a second signature over the
 * lines above it) so the token can't be swapped either. Both are one {@code key=value} per line with
 * {@code \}, newline and CR escaped. {@code tools/verify_evidence_package.py} implements the same rules.
 */
final class PackageFormat {

    static final String VERSION = "fmd-evidence-package/v1";
    static final String SIGNATURE_VERSION = "fmd-evidence-package-signature/v1";
    static final String MANIFEST = "PACKAGE-MANIFEST.txt";
    static final String SIGNATURE = "PACKAGE-SIGNATURE.txt";
    static final int MAX_ENTRIES = 200;
    static final long MAX_UNCOMPRESSED = 64L * 1024 * 1024;

    private static final List<String> SIGNATURE_KEYS = List.of(
            "algorithm", "signing_key_id", "manifest_sha256", "signature",
            "timestamp_token", "timestamp_time", "timestamp_authority");

    private PackageFormat() {}

    /** Header fields in the given order, then {@code file_count} and {@code file.N.path/sha256/size} per file. */
    static String manifest(Map<String, String> header, Map<String, byte[]> files) {
        StringBuilder sb = new StringBuilder(VERSION).append('\n');
        header.forEach((k, v) -> sb.append(k).append('=').append(CustodyChain.escape(v)).append('\n'));
        sb.append("file_count=").append(files.size()).append('\n');
        int n = 0;
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            n++;
            sb.append("file.").append(n).append(".path=").append(CustodyChain.escape(f.getKey())).append('\n');
            sb.append("file.").append(n).append(".sha256=").append(sha256Hex(f.getValue())).append('\n');
            sb.append("file.").append(n).append(".size=").append(f.getValue().length).append('\n');
        }
        return sb.toString();
    }

    record Signature(String keyId, String manifestSha256, String signature, String timestampToken, String timestampTime,
            String timestampAuthority, String seal) {}

    /** Everything the seal covers: all lines before {@code seal=}. */
    static String signatureBody(Signature s) {
        return SIGNATURE_VERSION + '\n'
                + "algorithm=" + Ed25519Keys.ALGORITHM + '\n'
                + "signing_key_id=" + CustodyChain.escape(s.keyId()) + '\n'
                + "manifest_sha256=" + CustodyChain.escape(s.manifestSha256()) + '\n'
                + "signature=" + CustodyChain.escape(s.signature()) + '\n'
                + "timestamp_token=" + CustodyChain.escape(s.timestampToken()) + '\n'
                + "timestamp_time=" + CustodyChain.escape(s.timestampTime()) + '\n'
                + "timestamp_authority=" + CustodyChain.escape(s.timestampAuthority()) + '\n';
    }

    static String signatureFile(Signature s) {
        return signatureBody(s) + "seal=" + CustodyChain.escape(s.seal()) + '\n';
    }

    /** Strict: the file must be exactly what {@link #signatureFile} would write for the values it holds. */
    static Signature parseSignature(String raw) {
        Map<String, String> f = parse(raw, SIGNATURE_VERSION);
        for (String k : SIGNATURE_KEYS) {
            if (!f.containsKey(k)) {
                throw new IllegalArgumentException(SIGNATURE + " has no " + k + " line.");
            }
        }
        if (!Ed25519Keys.ALGORITHM.equals(f.get("algorithm"))) {
            throw new IllegalArgumentException(SIGNATURE + " names algorithm " + f.get("algorithm") + ", not Ed25519.");
        }
        Signature s = new Signature(f.get("signing_key_id"), f.get("manifest_sha256"), f.get("signature"),
                blank(f.get("timestamp_token")), blank(f.get("timestamp_time")), blank(f.get("timestamp_authority")), f.get("seal"));
        if (s.seal() == null || !signatureFile(s).equals(raw)) {
            throw new IllegalArgumentException(SIGNATURE + " is not in the expected form (lines added, removed, reordered or reformatted).");
        }
        return s;
    }

    /** {@code key=value} lines after a version line; values unescaped. Duplicate or malformed lines are refused. */
    static Map<String, String> parse(String raw, String version) {
        if (raw == null || !raw.startsWith(version + "\n") || !raw.endsWith("\n")) {
            throw new IllegalArgumentException("Expected a " + version + " document.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = raw.substring(version.length() + 1).split("\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            int eq = lines[i].indexOf('=');
            if (eq <= 0 || out.put(lines[i].substring(0, eq), unescape(lines[i].substring(eq + 1))) != null) {
                throw new IllegalArgumentException("Line " + (i + 2) + " of the " + version + " document is malformed or repeated.");
            }
        }
        return out;
    }

    static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static byte[] zip(Map<String, byte[]> entries, Instant time) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTimeLocal(LocalDateTime.ofInstant(time, ZoneOffset.UTC));
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write the package: " + ex.getMessage(), ex);
        }
        return out.toByteArray();
    }

    /**
     * Reads every entry, refusing directories-escaping or duplicate names and anything past
     * {@link #MAX_ENTRIES} entries or {@link #MAX_UNCOMPRESSED} bytes (a crafted ZIP can expand hugely).
     */
    static Map<String, byte[]> unzip(byte[] data) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
                    throw new IllegalArgumentException("The package contains an unsafe file name: " + name);
                }
                if (out.size() >= MAX_ENTRIES) {
                    throw new IllegalArgumentException("The package has more than " + MAX_ENTRIES + " files.");
                }
                byte[] bytes = readLimited(zip, MAX_UNCOMPRESSED - total);
                total += bytes.length;
                if (out.put(name, bytes) != null) {
                    throw new IllegalArgumentException("The package contains " + name + " twice.");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Not a readable ZIP file: " + ex.getMessage());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Not a ZIP file, or an empty one.");
        }
        return out;
    }

    private static byte[] readLimited(InputStream in, long budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + (long) n > budget) {
                throw new IllegalArgumentException("The package expands to more than " + (MAX_UNCOMPRESSED >> 20) + " MB.");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(Ed25519Keys.sha256(data));
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String blank(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
