package com.forgeddetect.demo;

import com.forgeddetect.service.LabelledCopyIssued;
import com.forgeddetect.state.SharedState;
import java.time.Duration;
import java.util.Base64;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 25: in a public demo only synthetic media may be uploaded: the bundled samples, plus the
 * labelled copies this instance handed out for them (so "verify a labelled image" still works).
 * Labelled copies are remembered by SHA-256 in shared state, so every backend instance agrees.
 */
@Component
public class DemoUploadGuard {

    static final String ALLOWED = "demo:allowed:";
    static final String BUDGET = "ratelimit:demo:uploads";

    private final DemoProperties properties;
    private final DemoSamples samples;
    private final SharedState state;

    public DemoUploadGuard(DemoProperties properties, DemoSamples samples, SharedState state) {
        this.properties = properties;
        this.samples = samples;
        this.state = state;
    }

    public boolean active() {
        return properties.isEnabled() && properties.isPublicMode();
    }

    public boolean allowed(byte[] bytes) {
        String sha = DemoSamples.sha256(bytes);
        return samples.isBundled(sha) || state.get(ALLOWED + sha).isPresent();
    }

    /**
     * Takes one upload from the instance-wide budget. Every visitor shares it: behind a proxy there is no
     * trustworthy per-visitor key, and the point is to keep a flood from starving the models.
     *
     * @return seconds until the next upload is allowed, or 0 if this one may go ahead
     */
    public long takeUpload() {
        int limit = properties.getUploadsPerMinute();
        if (limit <= 0) {
            return 0;
        }
        SharedState.Bucket bucket = state.takeToken(BUDGET, limit, Duration.ofMinutes(1), true);
        return bucket.allowed() ? 0 : Math.max(1, (long) Math.ceil((1 - bucket.tokens()) * 60.0 / limit));
    }

    @EventListener
    public void onLabelled(LabelledCopyIssued event) {
        if (!active()) {
            return;
        }
        byte[] bytes = Base64.getDecoder().decode(event.base64());
        state.setIfAbsent(ALLOWED + DemoSamples.sha256(bytes), "1", memory());
    }

    /** Outlives a reset cycle, so a copy downloaded just before a reset can still be verified after it. */
    private Duration memory() {
        Duration interval = properties.scheduledReset() ? properties.getResetInterval() : Duration.ofHours(12);
        return interval.multipliedBy(2);
    }
}
