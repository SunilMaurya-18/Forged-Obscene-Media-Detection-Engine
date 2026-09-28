package com.forgeddetect.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs through HashiCorp Vault's Transit secrets engine (an {@code ed25519} key). The private key
 * never leaves Vault: this class sends the message digest to {@code /sign}, reads public keys from
 * {@code /keys}, and rotates with {@code /keys/<name>/rotate}. Each Vault key version is one key ID
 * here; retired versions stay verifiable because their public keys stay listed.
 */
public class VaultTransitSigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultTransitSigningKeyProvider.class);
    private static final Pattern VAULT_SIGNATURE = Pattern.compile("^vault:v(\\d+):(.+)$");
    private static final Duration CACHE_FOR = Duration.ofSeconds(30);

    private record Version(int version, String keyId, PublicKey key, Instant createdAt) {}

    private final URI base;
    private final String token;
    private final String namespace;
    private final String keyName;
    private final String mount;
    private final HttpClient http;
    private final ObjectMapper json;

    private volatile Map<Integer, Version> versions = Map.of();
    private volatile int latest;
    private volatile Instant loadedAt = Instant.EPOCH;

    public VaultTransitSigningKeyProvider(
            String address, String token, String namespace, String mount, String keyName, Duration timeout, ObjectMapper json) {
        if (address == null || address.isBlank() || token == null || token.isBlank()) {
            throw new IllegalStateException("report.signing.vault.address and report.signing.vault.token are required "
                    + "for REPORT_SIGNING_PROVIDER=vault");
        }
        this.base = URI.create(address.endsWith("/") ? address : address + "/");
        this.token = token;
        this.namespace = namespace;
        this.mount = mount;
        this.keyName = keyName;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        ensureKey();
        refresh();
        log.info("Report signing via Vault Transit key '{}' at {} (active {} = v{})", keyName, base, activeKeyId(), latest);
    }

    @Override
    public String kind() {
        return "vault-transit";
    }

    @Override
    public boolean production() {
        return true;
    }

    @Override
    public SignatureResult sign(byte[] message) {
        JsonNode body = call("POST", "sign/" + keyName, Map.of("input", Base64.getEncoder().encodeToString(message)));
        String sig = body.path("data").path("signature").asText("");
        Matcher m = VAULT_SIGNATURE.matcher(sig);
        if (!m.matches()) {
            throw new IllegalStateException("Unexpected Vault signature format");
        }
        int version = Integer.parseInt(m.group(1));
        Version v = versions.get(version);
        if (v == null) {
            refresh();
            v = versions.get(version);
        }
        if (v == null) {
            throw new IllegalStateException("Vault signed with unknown key version " + version);
        }
        return new SignatureResult(v.keyId(), Base64.getDecoder().decode(m.group(2)));
    }

    @Override
    public Optional<PublicKey> publicKey(String keyId) {
        Optional<Version> found = find(keyId);
        if (found.isEmpty()) {
            refresh();
            found = find(keyId);
        }
        return found.map(Version::key);
    }

    @Override
    public String activeKeyId() {
        refreshIfStale();
        return versions.get(latest).keyId();
    }

    @Override
    public List<KeyInfo> keys() {
        refreshIfStale();
        List<KeyInfo> out = new ArrayList<>();
        for (Version v : versions.values()) {
            out.add(new KeyInfo(v.keyId(), v.createdAt(), v.version() == latest, Ed25519Keys.pem("PUBLIC KEY", v.key().getEncoded())));
        }
        out.sort(Comparator.comparing(KeyInfo::active).thenComparing(KeyInfo::createdAt).reversed());
        return out;
    }

    @Override
    public synchronized KeyInfo rotate() {
        String previous = activeKeyId();
        call("POST", "keys/" + keyName + "/rotate", Map.of());
        refresh();
        log.warn("Report signing key rotated in Vault: {} -> {} (v{})", previous, activeKeyId(), latest);
        return keys().get(0);
    }

    private Optional<Version> find(String keyId) {
        return versions.values().stream().filter(v -> v.keyId().equals(keyId)).findFirst();
    }

    private void ensureKey() {
        if (call("GET", "keys/" + keyName, null) == null) {
            call("POST", "keys/" + keyName, Map.of("type", "ed25519"));
            log.warn("Created Vault Transit key '{}' (ed25519)", keyName);
        }
    }

    private void refreshIfStale() {
        if (Instant.now().isAfter(loadedAt.plus(CACHE_FOR))) {
            refresh();
        }
    }

    private synchronized void refresh() {
        JsonNode data = call("GET", "keys/" + keyName, null);
        if (data == null) {
            throw new IllegalStateException("Vault Transit key '" + keyName + "' does not exist");
        }
        data = data.path("data");
        if (!"ed25519".equals(data.path("type").asText())) {
            throw new IllegalStateException("Vault Transit key '" + keyName + "' must be type ed25519, is " + data.path("type").asText());
        }
        Map<Integer, Version> next = new java.util.HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = data.path("keys").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            int version = Integer.parseInt(e.getKey());
            String b64 = e.getValue().path("public_key").asText("");
            try {
                PublicKey key = Ed25519Keys.fromRaw(Base64.getDecoder().decode(b64));
                Instant created = parseTime(e.getValue().path("creation_time").asText(null));
                next.put(version, new Version(version, Ed25519Keys.keyId(key), key, created));
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                log.warn("Skipping unreadable Vault key version {}: {}", version, ex.getMessage());
            }
        }
        int newest = data.path("latest_version").asInt(0);
        if (!next.containsKey(newest)) {
            throw new IllegalStateException("Vault key '" + keyName + "' has no readable latest version");
        }
        versions = Map.copyOf(next);
        latest = newest;
        loadedAt = Instant.now();
    }

    private static Instant parseTime(String t) {
        try {
            return t == null ? Instant.EPOCH : java.time.OffsetDateTime.parse(t).toInstant();
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }

    /** @return the parsed body, or null on 404 */
    private JsonNode call(String method, String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve("v1/" + mount + "/" + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Vault-Token", token)
                    .header("Accept", "application/json");
            if (namespace != null && !namespace.isBlank()) {
                req.header("X-Vault-Namespace", namespace);
            }
            if (body == null) {
                req.GET();
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
            HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 404 && body == null) {
                return null;
            }
            if (res.statusCode() >= 300) {
                throw new IllegalStateException("Vault " + method + " " + path + " failed: HTTP " + res.statusCode());
            }
            return res.body().length == 0 ? json.createObjectNode() : json.readTree(res.body());
        } catch (IOException ex) {
            throw new IllegalStateException("Vault " + method + " " + path + " failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Vault", ex);
        }
    }
}
