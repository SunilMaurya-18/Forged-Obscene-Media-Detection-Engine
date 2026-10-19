package com.forgeddetect.config;

import com.forgeddetect.investigation.InvestigationProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Refuses to start outside the {@code test} and {@code dev} profiles while weak or published
 * secrets are configured. Local compose uses {@code APP_PROFILE=dev}; production must supply
 * real credentials.
 */
public class ProductionSecretsGuard implements EnvironmentPostProcessor, Ordered {

    static final Set<String> RELAXED_PROFILES = Set.of("test", "dev");
    /** @deprecated use {@link #RELAXED_PROFILES} */
    static final Set<String> TEST_PROFILES = RELAXED_PROFILES;
    static final int MIN_PASSWORD = 12;
    static final int MIN_PSEUDONYM_KEY = 32;
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "moderator", "reviewer", "senior", "apac", "admin", "forged", "password", "changeme");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isRelaxedProfile(environment)) {
            return;
        }
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with profile(s) " + Arrays.toString(environment.getActiveProfiles())
                    + " while insecure defaults are configured:\n  - " + String.join("\n  - ", problems)
                    + "\nSet real values in .env (see .env.production.example), or APP_PROFILE=dev for local compose.");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static boolean isRelaxedProfile(Environment env) {
        String[] active = env.getActiveProfiles();
        String[] effective = active.length > 0 ? active : env.getDefaultProfiles();
        return Arrays.stream(effective).anyMatch(RELAXED_PROFILES::contains);
    }

    /** @deprecated use {@link #isRelaxedProfile(Environment)} */
    static boolean isTestProfile(Environment env) {
        return isRelaxedProfile(env);
    }

    static List<String> problems(Environment env) {
        List<String> out = new ArrayList<>();
        String username = env.getProperty("app.security.moderator-username", "moderator");
        String password = env.getProperty("app.security.moderator-password", "");
        if (weak(username, password)) {
            out.add("MODERATOR_PASSWORD is a published value, equals the username or is shorter than " + MIN_PASSWORD + " characters");
        }
        String extra = env.getProperty("app.security.extra-moderators", "");
        for (String entry : extra.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length >= 2 && weak(parts[0], parts[1])) {
                out.add("MODERATOR_EXTRA_ACCOUNTS has a weak password for '" + parts[0]
                        + "' (set real passwords, or MODERATOR_EXTRA_ACCOUNTS= to seed none)");
            }
        }
        String dbPassword = env.getProperty("spring.datasource.password", "");
        if (dbPassword.isBlank() || WEAK_PASSWORDS.contains(dbPassword)) {
            out.add("SPRING_DATASOURCE_PASSWORD / POSTGRES_PASSWORD is empty or a published default");
        }
        if (!env.getProperty("api.bootstrap-key", "").isBlank()) {
            out.add("api.bootstrap-key / API_BOOTSTRAP_KEY is set (test-only); leave it empty and issue keys from /admin or /api-access");
        }
        String pseudonymKey = env.getProperty("investigation.pseudonym-key", InvestigationProperties.DEV_PSEUDONYM_KEY);
        if (pseudonymKey.length() < MIN_PSEUDONYM_KEY || InvestigationProperties.DEV_PSEUDONYM_KEY.equals(pseudonymKey)) {
            out.add("INVESTIGATION_PSEUDONYM_KEY is the published default or shorter than " + MIN_PSEUDONYM_KEY
                    + " characters");
        }
        if ("file".equalsIgnoreCase(env.getProperty("report.signing.provider", "file"))
                && !env.getProperty("report.signing.allow-file-keys", Boolean.class, false)) {
            out.add("REPORT_SIGNING_PROVIDER=file keeps the report-signing private key on local disk; use vault "
                    + "(a KMS), or set REPORT_SIGNING_ALLOW_FILE_KEYS=true to accept that explicitly");
        }
        return out;
    }

    private static boolean weak(String username, String password) {
        return password == null || password.length() < MIN_PASSWORD || password.equalsIgnoreCase(username)
                || WEAK_PASSWORDS.contains(password.toLowerCase());
    }
}
