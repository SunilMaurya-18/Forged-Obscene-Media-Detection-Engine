package com.forgeddetect.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** Phase 24 DoD: the backend fails to start in a non-dev profile with default secrets. */
class DemoSecretsGuardTest {

    private final DemoSecretsGuard guard = new DemoSecretsGuard();

    /** What application.yml resolves to with no environment overrides. */
    private static MockEnvironment defaults() {
        MockEnvironment env = new MockEnvironment();
        env.setDefaultProfiles("dev");
        env.setProperty("app.security.moderator-username", "moderator");
        env.setProperty("app.security.moderator-password", "moderator");
        env.setProperty("app.security.extra-moderators", "reviewer:reviewer:MODERATOR:core,senior:senior:SENIOR_MODERATOR:core");
        env.setProperty("spring.datasource.password", "forged");
        env.setProperty("api.demo-key", "fmd_live_demo_local_only_do_not_use_in_prod");
        env.setProperty("compliance.seed-demo", "true");
        env.setProperty("report.signing.provider", "file");
        env.setProperty("demo.enabled", "true");
        return env;
    }

    @Test
    void devProfileAllowsTheDemoDefaults() {
        guard.postProcessEnvironment(defaults(), new SpringApplication());
        MockEnvironment explicitDev = defaults();
        explicitDev.setActiveProfiles("dev");
        guard.postProcessEnvironment(explicitDev, new SpringApplication());
    }

    @Test
    void prodProfileRefusesEveryDemoSecret() {
        MockEnvironment env = defaults();
        env.setActiveProfiles("prod");
        assertThat(DemoSecretsGuard.problems(env)).hasSize(8);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start with profile(s) [prod]")
                .hasMessageContaining("MODERATOR_PASSWORD")
                .hasMessageContaining("'reviewer'")
                .hasMessageContaining("POSTGRES_PASSWORD")
                .hasMessageContaining("API_DEMO_KEY")
                .hasMessageContaining("COMPLIANCE_SEED_DEMO")
                .hasMessageContaining("REPORT_SIGNING_PROVIDER=file")
                .hasMessageContaining("DEMO_ENABLED");
    }

    @Test
    void prodProfileStartsWithRealSecrets() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("app.security.moderator-username", "trust-safety-lead");
        env.setProperty("app.security.moderator-password", "c0rrect-horse-battery-staple");
        env.setProperty("app.security.extra-moderators", "");
        env.setProperty("spring.datasource.password", "a-long-database-secret");
        env.setProperty("api.demo-key", "");
        env.setProperty("compliance.seed-demo", "false");
        env.setProperty("report.signing.provider", "vault");
        guard.postProcessEnvironment(env, new SpringApplication());

        env.setProperty("report.signing.provider", "file");
        assertThat(DemoSecretsGuard.problems(env)).hasSize(1);
        env.setProperty("report.signing.allow-file-keys", "true");
        assertThat(DemoSecretsGuard.problems(env)).isEmpty();
    }
}
