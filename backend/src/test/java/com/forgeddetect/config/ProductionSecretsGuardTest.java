package com.forgeddetect.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecretsGuardTest {

    @Test
    void flagsPublishedDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.security.moderator-username", "moderator");
        env.setProperty("app.security.moderator-password", "moderator");
        env.setProperty("app.security.extra-moderators", "reviewer:reviewer:MODERATOR:core,senior:senior:SENIOR_MODERATOR:core");
        env.setProperty("spring.datasource.password", "forged");
        env.setProperty("api.bootstrap-key", "fmd_live_test_only_do_not_use");
        env.setProperty("investigation.pseudonym-key", "dev-only-account-pseudonym-key-change-me");
        env.setProperty("report.signing.provider", "file");
        env.setProperty("report.signing.allow-file-keys", "false");

        assertThat(ProductionSecretsGuard.problems(env)).hasSize(7);
    }

    @Test
    void acceptsStrongProductionValues() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.security.moderator-username", "owner");
        env.setProperty("app.security.moderator-password", "sufficiently-long-secret");
        env.setProperty("app.security.extra-moderators", "");
        env.setProperty("spring.datasource.password", "db-password-long-enough");
        env.setProperty("api.bootstrap-key", "");
        env.setProperty("investigation.pseudonym-key", "0123456789abcdef0123456789abcdef");
        env.setProperty("report.signing.provider", "file");
        env.setProperty("report.signing.allow-file-keys", "true");

        assertThat(ProductionSecretsGuard.problems(env)).isEmpty();
    }

    @Test
    void testProfileSkipsGuard() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertThat(ProductionSecretsGuard.isRelaxedProfile(env)).isTrue();
    }

    @Test
    void devProfileSkipsGuard() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThat(ProductionSecretsGuard.isRelaxedProfile(env)).isTrue();
    }
}
