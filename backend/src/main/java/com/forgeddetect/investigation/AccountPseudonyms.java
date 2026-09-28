package com.forgeddetect.investigation;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 26: turns a partner's account ID into {@code HMAC-SHA256(key, platform + "\n" + accountId)}.
 * The raw ID is never stored or logged. Scoping by platform means the same pseudonym never links
 * accounts across platforms, and without the key nobody (including the partner) can confirm a guess.
 */
@Component
@EnableConfigurationProperties(InvestigationProperties.class)
public class AccountPseudonyms {

    static final int MAX_ACCOUNT_ID = 256;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public AccountPseudonyms(InvestigationProperties properties) {
        String secret = properties.getPseudonymKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("investigation.pseudonym-key (INVESTIGATION_PSEUDONYM_KEY) must be set");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * @param platform normalised platform name
     * @return 64 hex characters, or null when no account was named
     * @throws IllegalArgumentException for an over-long account ID
     */
    public String of(String platform, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        String id = accountId.strip();
        if (id.length() > MAX_ACCOUNT_ID) {
            throw new IllegalArgumentException("accountId must be at most " + MAX_ACCOUNT_ID + " characters");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((platform + "\n" + id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** What the console shows: enough to tell pseudonyms apart, not the full value. */
    public static String shortForm(String accountKey) {
        return accountKey == null ? null : accountKey.substring(0, Math.min(12, accountKey.length()));
    }
}
