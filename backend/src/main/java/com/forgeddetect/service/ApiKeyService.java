package com.forgeddetect.service;

import com.forgeddetect.model.ApiClient;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.PlanTier;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ApiClientRepository;
import com.forgeddetect.security.ApiProperties;
import com.forgeddetect.security.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues and checks API keys. Keys are 256-bit random secrets, so a single SHA-256 is a safe,
 * index-friendly way to store them (no salt or slow hash needed, unlike passwords).
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    static final String KEY_PREFIX = "fmd_live_";
    private static final int PREFIX_CHARS = KEY_PREFIX.length() + 6;
    /** Name used when {@code api.bootstrap-key} seeds a client (integration tests only). */
    public static final String BOOTSTRAP_CLIENT = "Bootstrap Client";

    private final ApiClientRepository clients;
    private final AnalysisResultRepository analyses;
    private final ApiProperties props;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(
            ApiClientRepository clients,
            AnalysisResultRepository analyses,
            ApiProperties props,
            RateLimiter rateLimiter,
            Clock clock) {
        this.clients = clients;
        this.analyses = analyses;
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** The raw key is only ever returned here; callers must show it once and discard it. */
    public record IssuedKey(ApiClient client, String apiKey) {}

    @Transactional
    public IssuedKey create(String name, PlanTier plan) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required (max 80 characters)");
        }
        if (clients.existsByNameIgnoreCase(trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A client named '" + trimmed + "' already exists");
        }
        String key = newKey();
        ApiClient saved = clients.save(new ApiClient(trimmed, plan != null ? plan : PlanTier.FREE, hash(key), prefix(key)));
        return new IssuedKey(saved, key);
    }

    /** Replaces the key; the old one stops working immediately. */
    @Transactional
    public IssuedKey rotate(Long id) {
        ApiClient client = find(id);
        if (!client.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Revoked clients cannot be rotated");
        }
        String key = newKey();
        client.setKeyHash(hash(key));
        client.setKeyPrefix(prefix(key));
        return new IssuedKey(clients.save(client), key);
    }

    @Transactional
    public ApiClient revoke(Long id) {
        ApiClient client = find(id);
        if (client.isActive()) {
            client.setRevokedAt(clock.instant());
            rateLimiter.reset(client.getId());
        }
        return clients.save(client);
    }

    @Transactional
    public ApiClient changePlan(Long id, PlanTier plan) {
        ApiClient client = find(id);
        client.setPlan(plan);
        return clients.save(client);
    }

    @Transactional(readOnly = true)
    public List<ApiClient> list() {
        return clients.findAllByOrderByCreatedAtDesc();
    }

    /** Active client for a presented key, if any. */
    @Transactional(readOnly = true)
    public Optional<ApiClient> authenticate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(KEY_PREFIX) || presentedKey.length() > 128) {
            return Optional.empty();
        }
        return clients.findByKeyHash(hash(presentedKey)).filter(ApiClient::isActive);
    }

    @Transactional
    public void recordRequest(Long id) {
        clients.recordRequest(id, clock.instant());
    }

    @Transactional
    public void recordThrottled(Long id) {
        clients.recordThrottled(id, clock.instant());
    }

    public int limitFor(ApiClient client) {
        return props.limitFor(client.getPlan());
    }

    public int limitFor(PlanTier plan) {
        return props.limitFor(plan);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> usage(Long clientId) {
        ApiClient client = find(clientId);
        RateLimiter.Result rate = rateLimiter.peek(client.getId(), limitFor(client));

        Map<String, Object> clientView = new LinkedHashMap<>();
        clientView.put("name", client.getName());
        clientView.put("plan", client.getPlan().name());
        clientView.put("keyPrefix", client.getKeyPrefix());
        clientView.put("createdAt", client.getCreatedAt().toString());

        Map<String, Object> rateView = new LinkedHashMap<>();
        rateView.put("limitPerMinute", rate.limit());
        rateView.put("remaining", rate.remaining());
        rateView.put("resetSeconds", rate.resetSeconds());

        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("total", client.getRequestCount());
        requests.put("throttled", client.getThrottledCount());
        requests.put("lastUsedAt", client.getLastUsedAt() != null ? client.getLastUsedAt().toString() : null);

        Map<String, Long> byDecision = new LinkedHashMap<>();
        for (Decision d : Decision.values()) {
            byDecision.put(d.name(), analyses.countByApiClientIdAndDecision(client.getId(), d));
        }
        Map<String, Object> analysisView = new LinkedHashMap<>();
        analysisView.put("total", analyses.countByApiClientId(client.getId()));
        analysisView.put("last24h",
                analyses.countByApiClientIdAndCreatedAtAfter(client.getId(), clock.instant().minus(Duration.ofHours(24))));
        analysisView.put("byDecision", byDecision);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", clientView);
        body.put("rateLimit", rateView);
        body.put("requests", requests);
        body.put("analyses", analysisView);
        return body;
    }

    /** Test/bootstrap only: seed a FREE client from {@code api.bootstrap-key}. Refused outside test by ProductionSecretsGuard. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedBootstrapClient() {
        String key = props.getBootstrapKey();
        if (key == null || key.isBlank()) {
            return;
        }
        if (!key.startsWith(KEY_PREFIX) || key.length() < KEY_PREFIX.length() + 16) {
            log.warn("api.bootstrap-key ignored: it must start with '{}' and have at least 16 more characters", KEY_PREFIX);
            return;
        }
        if (clients.findByKeyHash(hash(key)).isPresent() || clients.existsByNameIgnoreCase(BOOTSTRAP_CLIENT)) {
            return;
        }
        clients.save(new ApiClient(BOOTSTRAP_CLIENT, PlanTier.FREE, hash(key), prefix(key)));
        log.info("Seeded API client '{}' from api.bootstrap-key", BOOTSTRAP_CLIENT);
    }

    private ApiClient find(Long id) {
        return clients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API client not found: " + id));
    }

    private String newKey() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static String prefix(String key) {
        return key.substring(0, Math.min(PREFIX_CHARS, key.length()));
    }

    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
