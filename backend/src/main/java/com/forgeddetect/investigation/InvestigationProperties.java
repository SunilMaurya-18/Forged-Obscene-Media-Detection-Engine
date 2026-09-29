package com.forgeddetect.investigation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "investigation")
public class InvestigationProperties {

    /** Published in application.yml; {@code DemoSecretsGuard} refuses it outside the dev profile. */
    public static final String DEV_PSEUDONYM_KEY = "dev-only-account-pseudonym-key-change-me";

    /** HMAC key that turns reported account IDs into pseudonyms. Rotating it unlinks every existing pseudonym. */
    private String pseudonymKey = DEV_PSEUDONYM_KEY;

    private final Handover handover = new Handover();

    public String getPseudonymKey() {
        return pseudonymKey;
    }

    public void setPseudonymKey(String pseudonymKey) {
        this.pseudonymKey = pseudonymKey;
    }

    public Handover getHandover() {
        return handover;
    }

    /** Phase 28: legal requests and evidence packages for investigators. */
    public static class Handover {

        /** How long after it is issued a package can be downloaded (never beyond the request's expiry). */
        private Duration downloadWindow = Duration.ofHours(24);

        /** Longest a legal request may run before it has to be filed again. */
        private Duration maxRequestTerm = Duration.ofDays(180);

        public Duration getDownloadWindow() {
            return downloadWindow;
        }

        public void setDownloadWindow(Duration downloadWindow) {
            this.downloadWindow = downloadWindow;
        }

        public Duration getMaxRequestTerm() {
            return maxRequestTerm;
        }

        public void setMaxRequestTerm(Duration maxRequestTerm) {
            this.maxRequestTerm = maxRequestTerm;
        }
    }
}
