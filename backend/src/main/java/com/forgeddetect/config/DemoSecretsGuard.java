package com.forgeddetect.config;

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
 * Phase 24: the backend refuses to start outside the {@code dev} profile while any published demo
 * secret or demo seeding is configured. {@code dev} is the default profile, so local runs and the
 * demo compose stack are unaffected; {@code SPRING_PROFILES_ACTIVE=prod} (or any other profile)
 * turns the check on.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor}, before any bean, database connection or seeder.
 */
public class DemoSecretsGuard implements EnvironmentPostProcessor, Ordered {

    static final Set<String> DEV_PROFILES = Set.of("dev", "test");
    static final int MIN_PASSWORD = 12;
    private static final Set<String> DEMO_PASSWORDS = Set.of("moderator", "reviewer", "senior", "apac", "forged", "password", "changeme");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isDevProfile(environment)) {
            return;
        }
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with profile(s) " + Arrays.toString(environment.getActiveProfiles())
                    + " while demo secrets are configured:\n  - " + String.join("\n  - ", problems)
                    + "\nSet real values, or run with SPRING_PROFILES_ACTIVE=dev for a local demo.");
        }
    }

    @Override
    public int getOrder() {
        // After application.yml and profile documents are loaded
        return Ordered.LOWEST_PRECEDENCE;
    }

    static boolean isDevProfile(Environment env) {
        String[] active = env.getActiveProfiles();
        String[] effective = active.length > 0 ? active : env.getDefaultProfiles();
        return Arrays.stream(effective).anyMatch(DEV_PROFILES::contains);
    }

    static List<String> problems(Environment env) {
        List<String> out = new ArrayList<>();
        String username = env.getProperty("app.security.moderator-username", "moderator");
        String password = env.getProperty("app.security.moderator-password", "");
        if (weak(username, password)) {
            out.add("MODERATOR_PASSWORD is a demo value, equals the username or is shorter than " + MIN_PASSWORD + " characters");
        }
        String extra = env.getProperty("app.security.extra-moderators", "");
        for (String entry : extra.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length >= 2 && weak(parts[0], parts[1])) {
                out.add("MODERATOR_EXTRA_ACCOUNTS has a demo or weak password for '" + parts[0]
                        + "' (set real passwords, or MODERATOR_EXTRA_ACCOUNTS= to seed none)");
            }
        }
        String dbPassword = env.getProperty("spring.datasource.password", "");
        if (dbPassword.isBlank() || DEMO_PASSWORDS.contains(dbPassword)) {
            out.add("SPRING_DATASOURCE_PASSWORD / POSTGRES_PASSWORD is empty or the demo value");
        }
        if (!env.getProperty("api.demo-key", "").isBlank()) {
            out.add("API_DEMO_KEY is set (it seeds a published partner API key); leave it empty");
        }
        if (env.getProperty("compliance.seed-demo", Boolean.class, false)) {
            out.add("COMPLIANCE_SEED_DEMO=true would insert sample complaints into the real database");
        }
        if (env.getProperty("demo.enabled", Boolean.class, false)) {
            out.add("DEMO_ENABLED=true exposes the demo story and a reset that deletes all moderation data; "
                    + "a hosted public demo runs with SPRING_PROFILES_ACTIVE=dev on its own throwaway database");
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
                || DEMO_PASSWORDS.contains(password.toLowerCase());
    }
}
