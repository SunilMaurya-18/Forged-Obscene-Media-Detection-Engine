package com.forgeddetect.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Phase 25: the synthetic files bundled with the backend ({@code resources/demo-samples}). They are
 * the only uploads a public demo accepts, so every visitor can try every pipeline without bringing
 * their own media.
 */
@Component
public class DemoSamples {

    /** {@code kind} tells the frontend which copy to show; the files themselves carry no people. */
    public record Sample(String name, String kind, String mimeType, byte[] bytes, String sha256) {
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("kind", kind);
            m.put("mimeType", mimeType);
            m.put("size", bytes.length);
            m.put("sha256", sha256);
            m.put("url", "/api/demo/samples/" + name);
            return m;
        }
    }

    private static final List<String[]> CATALOGUE = List.of(
            new String[] {"synthetic-stripes.jpg", "review", "image/jpeg"},
            new String[] {"prescreen-demo.png", "known", "image/png"},
            new String[] {"c2pa-sample.jpg", "credentials", "image/jpeg"},
            new String[] {"sample-clip.mp4", "video", "video/mp4"});

    private final Map<String, Sample> byName = new LinkedHashMap<>();
    private final Set<String> hashes;

    public DemoSamples() {
        for (String[] entry : CATALOGUE) {
            byte[] bytes = read("demo-samples/" + entry[0]);
            byName.put(entry[0], new Sample(entry[0], entry[1], entry[2], bytes, sha256(bytes)));
        }
        hashes = byName.values().stream().map(Sample::sha256).collect(Collectors.toUnmodifiableSet());
    }

    public List<Sample> all() {
        return List.copyOf(byName.values());
    }

    public Optional<Sample> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isBundled(String sha256) {
        return hashes.contains(sha256);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing bundled demo sample " + path, e);
        }
    }
}
