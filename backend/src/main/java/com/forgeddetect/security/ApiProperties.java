package com.forgeddetect.security;

import com.forgeddetect.model.PlanTier;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-plan rate limits for {@code /api/v1} and an optional bootstrap key for automated tests. */
@ConfigurationProperties(prefix = "api")
public class ApiProperties {

    /** Requests per minute for each plan (token bucket: this many in a burst, refilled evenly over 60 s). */
    private Map<PlanTier, Integer> rateLimits = new EnumMap<>(Map.of(
            PlanTier.FREE, 10,
            PlanTier.PRO, 60,
            PlanTier.ENTERPRISE, 600));

    /** When set, a FREE-plan bootstrap client with exactly this key is created on startup (tests only). */
    private String bootstrapKey = "";

    public int limitFor(PlanTier plan) {
        return Math.max(1, rateLimits.getOrDefault(plan, 10));
    }

    public Map<PlanTier, Integer> getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(Map<PlanTier, Integer> rateLimits) {
        this.rateLimits.putAll(rateLimits);
    }

    public String getBootstrapKey() {
        return bootstrapKey;
    }

    public void setBootstrapKey(String bootstrapKey) {
        this.bootstrapKey = bootstrapKey;
    }
}
