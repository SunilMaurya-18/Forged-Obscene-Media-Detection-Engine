package com.forgeddetect.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Phase 23: moderator exposure limits. Bound from {@code wellbeing.*}. */
@ConfigurationProperties(prefix = "wellbeing")
public class WellbeingProperties {

    /** Items a moderator may act on or reveal in one session before a break is enforced. */
    private int exposureLimit = 40;

    private Duration breakDuration = Duration.ofMinutes(15);

    /** Inactivity after which the session counter starts again (an unrecorded break). */
    private Duration idleReset = Duration.ofHours(2);

    /** Shown in the queue and on the break screen; a relative path is a frontend route. */
    private String supportUrl = "/support";

    public int getExposureLimit() {
        return exposureLimit;
    }

    public void setExposureLimit(int exposureLimit) {
        this.exposureLimit = exposureLimit;
    }

    public Duration getBreakDuration() {
        return breakDuration;
    }

    public void setBreakDuration(Duration breakDuration) {
        this.breakDuration = breakDuration;
    }

    public Duration getIdleReset() {
        return idleReset;
    }

    public void setIdleReset(Duration idleReset) {
        this.idleReset = idleReset;
    }

    public String getSupportUrl() {
        return supportUrl;
    }

    public void setSupportUrl(String supportUrl) {
        this.supportUrl = supportUrl;
    }
}
