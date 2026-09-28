package com.forgeddetect.demo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 25: one-click demo story and the hosted public demo.
 *
 * <ul>
 *   <li>{@code enabled}: the story endpoints and bundled synthetic samples exist at all.
 *   <li>{@code public-mode}: a hosted instance for strangers. Anyone may reset (with a cooldown), the
 *       instance resets itself every {@code reset-interval}, the moderator logins are shown on the tour,
 *       and uploads are limited to the bundled synthetic samples and their labelled copies.
 * </ul>
 */
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private boolean enabled = false;
    private boolean publicMode = false;
    /** Public mode only; zero or negative turns the scheduled reset off. */
    private Duration resetInterval = Duration.ofHours(6);
    /** Public mode only: minimum gap between two visitor-triggered resets. */
    private Duration resetCooldown = Duration.ofMinutes(2);
    /** Public mode only: uploads per minute across the whole instance (every visitor shares it). */
    private int uploadsPerMinute = 60;
    /** Jurisdiction whose rules the story's clocks follow. */
    private String jurisdiction = "IN";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPublicMode() {
        return publicMode;
    }

    public void setPublicMode(boolean publicMode) {
        this.publicMode = publicMode;
    }

    public Duration getResetInterval() {
        return resetInterval;
    }

    public void setResetInterval(Duration resetInterval) {
        this.resetInterval = resetInterval;
    }

    public Duration getResetCooldown() {
        return resetCooldown;
    }

    public void setResetCooldown(Duration resetCooldown) {
        this.resetCooldown = resetCooldown;
    }

    public int getUploadsPerMinute() {
        return uploadsPerMinute;
    }

    public void setUploadsPerMinute(int uploadsPerMinute) {
        this.uploadsPerMinute = uploadsPerMinute;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    public boolean scheduledReset() {
        return enabled && publicMode && resetInterval != null && !resetInterval.isZero() && !resetInterval.isNegative();
    }
}
