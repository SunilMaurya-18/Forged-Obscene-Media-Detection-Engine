package com.forgeddetect.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "investigation")
public class InvestigationProperties {

    /** Published in application.yml; {@code DemoSecretsGuard} refuses it outside the dev profile. */
    public static final String DEV_PSEUDONYM_KEY = "dev-only-account-pseudonym-key-change-me";

    /** HMAC key that turns reported account IDs into pseudonyms. Rotating it unlinks every existing pseudonym. */
    private String pseudonymKey = DEV_PSEUDONYM_KEY;

    public String getPseudonymKey() {
        return pseudonymKey;
    }

    public void setPseudonymKey(String pseudonymKey) {
        this.pseudonymKey = pseudonymKey;
    }
}
