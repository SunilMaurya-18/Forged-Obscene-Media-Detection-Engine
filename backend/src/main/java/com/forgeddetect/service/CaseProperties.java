package com.forgeddetect.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Phase 21: public self-service complaints (case number + PIN). */
@ConfigurationProperties(prefix = "complaints")
public class CaseProperties {

    /** Wrong PINs allowed per case number before lookups for it are locked. */
    private int maxPinAttempts = 5;

    private Duration lockout = Duration.ofMinutes(15);

    /** Optional: POST a JSON event here on every case status change (blank = log only). */
    private String notifyWebhookUrl = "";

    public int getMaxPinAttempts() {
        return maxPinAttempts;
    }

    public void setMaxPinAttempts(int maxPinAttempts) {
        this.maxPinAttempts = maxPinAttempts;
    }

    public Duration getLockout() {
        return lockout;
    }

    public void setLockout(Duration lockout) {
        this.lockout = lockout;
    }

    public String getNotifyWebhookUrl() {
        return notifyWebhookUrl;
    }

    public void setNotifyWebhookUrl(String notifyWebhookUrl) {
        this.notifyWebhookUrl = notifyWebhookUrl;
    }
}
