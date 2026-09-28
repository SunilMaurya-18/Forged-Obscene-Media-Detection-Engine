package com.forgeddetect.service;

import com.forgeddetect.model.KnownHash;
import com.forgeddetect.model.KnownHashSource;
import com.forgeddetect.repository.KnownHashRepository;
import java.math.BigInteger;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 18: compares a hash computed on the user's device against hashes of confirmed abusive content.
 *
 * <p>Only a hash ever arrives here. The answer is a bare match / no-match: no distance, no source and
 * no row ID, so the endpoint can't be used to tune an image until it slips just under the threshold
 * or to learn where a hash came from.
 */
@Service
public class PrescreenService {

    private static final Logger log = LoggerFactory.getLogger(PrescreenService.class);
    private static final Map<String, Pattern> FORMATS = Map.of(
            "phash", Pattern.compile("^[0-9a-f]{16}$"),
            "pdq", Pattern.compile("^[0-9a-f]{64}$"));

    private final KnownHashRepository repository;
    private final PrescreenProperties properties;
    private final Clock clock;

    public PrescreenService(KnownHashRepository repository, PrescreenProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException for an unknown algorithm or a malformed hash */
    @Transactional(readOnly = true)
    public boolean matches(String hash, String algorithm) {
        String algo = normalizeAlgorithm(algorithm);
        String h = normalizeHash(hash, algo);
        int max = maxDistance(algo);
        BigInteger probe = new BigInteger(h, 16);
        // A linear scan is fine for thousands of rows; millions would want a BK-tree or multi-index hashing.
        for (KnownHash known : repository.findByAlgorithm(algo)) {
            if (probe.xor(new BigInteger(known.getHash(), 16)).bitCount() <= max) {
                return true;
            }
        }
        return false;
    }

    /** Idempotent. Returns true if a new row was written. */
    @Transactional
    public boolean add(String hash, String algorithm, KnownHashSource source, String note) {
        String algo = normalizeAlgorithm(algorithm);
        String h = normalizeHash(hash, algo);
        if (repository.existsByAlgorithmAndHash(algo, h)) {
            return false;
        }
        repository.save(new KnownHash(h, algo, source, clock.instant(), truncate(note)));
        return true;
    }

    /** Moderator confirmed a takedown: future pre-screens of visually similar images will match. */
    @Transactional
    public void confirmTakedown(String phash, Long analysisId) {
        if (phash == null || !FORMATS.get("phash").matcher(phash.toLowerCase(Locale.ROOT)).matches()) {
            return;
        }
        if (add(phash, "phash", KnownHashSource.INTERNAL_CONFIRMED, takedownNote(analysisId))) {
            log.info("Known-hash list: added pHash of analysis #{}", analysisId);
        }
    }

    /**
     * A takedown was reversed (appeal granted, override confirmed): drop the hash this item's takedown
     * added, so re-uploads of restored content stop matching. Imported or seeded hashes are never touched.
     */
    @Transactional
    public void releaseTakedown(String phash, Long analysisId) {
        if (phash == null || !FORMATS.get("phash").matcher(phash.toLowerCase(Locale.ROOT)).matches()) {
            return;
        }
        List<KnownHash> rows = repository.findByAlgorithmAndHashAndSourceAndNote(
                "phash", phash.toLowerCase(Locale.ROOT), KnownHashSource.INTERNAL_CONFIRMED, takedownNote(analysisId));
        if (!rows.isEmpty()) {
            repository.deleteAll(rows);
            log.info("Known-hash list: removed pHash of analysis #{} (takedown reversed)", analysisId);
        }
    }

    private static String takedownNote(Long analysisId) {
        return "Moderator-confirmed takedown of analysis #" + analysisId;
    }

    /** Bulk import (e.g. a StopNCII hash list); malformed entries are counted, not fatal. */
    @Transactional
    public Map<String, Integer> importHashes(List<String> hashes, String algorithm, KnownHashSource source, String note) {
        int imported = 0;
        int skipped = 0;
        int invalid = 0;
        for (String hash : hashes) {
            try {
                if (add(hash, algorithm, source, note)) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (IllegalArgumentException ex) {
                invalid++;
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("imported", imported);
        out.put("skipped", skipped);
        out.put("invalid", invalid);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (KnownHashSource s : KnownHashSource.values()) {
            bySource.put(s.name(), repository.countBySource(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", repository.count());
        body.put("bySource", bySource);
        body.put("algorithms", FORMATS.keySet().stream().sorted().toList());
        body.put("phashMaxDistance", properties.getPhashMaxDistance());
        body.put("pdqMaxDistance", properties.getPdqMaxDistance());
        return body;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        int added = 0;
        for (String hash : properties.getSeedHashes()) {
            if (hash == null || hash.isBlank()) {
                continue;
            }
            try {
                if (add(hash.trim(), "phash", KnownHashSource.INTERNAL_CONFIRMED, "Demo seed: synthetic test pattern")) {
                    added++;
                }
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring malformed prescreen seed hash '{}'", hash);
            }
        }
        if (added > 0) {
            log.info("Known-hash list: seeded {} demo hash(es)", added);
        }
    }

    private int maxDistance(String algo) {
        return "pdq".equals(algo) ? properties.getPdqMaxDistance() : properties.getPhashMaxDistance();
    }

    public static String normalizeAlgorithm(String algorithm) {
        String algo = algorithm == null || algorithm.isBlank() ? "phash" : algorithm.trim().toLowerCase(Locale.ROOT);
        if (!FORMATS.containsKey(algo)) {
            throw new IllegalArgumentException("algorithm must be one of " + FORMATS.keySet().stream().sorted().toList());
        }
        return algo;
    }

    static String normalizeHash(String hash, String algo) {
        String h = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
        if (!FORMATS.get(algo).matcher(h).matches()) {
            throw new IllegalArgumentException(
                    "phash".equals(algo) ? "hash must be 16 hex characters (64-bit pHash)" : "hash must be 64 hex characters (256-bit PDQ)");
        }
        return h;
    }

    private static String truncate(String note) {
        return note == null || note.length() <= 160 ? note : note.substring(0, 160);
    }
}
