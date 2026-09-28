package com.forgeddetect.state;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 24 DoD "two backend instances share rate-limit state through Redis": two independent Redis
 * clients (as two backend instances would have) against one real Redis. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisSharedStateTest extends SharedStateContract {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory first;
    private static LettuceConnectionFactory second;

    @BeforeAll
    static void connect() {
        first = factory();
        second = factory();
    }

    @AfterAll
    static void disconnect() {
        first.destroy();
        second.destroy();
    }

    private static LettuceConnectionFactory factory() {
        LettuceConnectionFactory f = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        f.afterPropertiesSet();
        f.start();
        return f;
    }

    @Override
    SharedState instanceA() {
        return new RedisSharedState(new StringRedisTemplate(first), "fmd-test:");
    }

    @Override
    SharedState instanceB() {
        return new RedisSharedState(new StringRedisTemplate(second), "fmd-test:");
    }
}
