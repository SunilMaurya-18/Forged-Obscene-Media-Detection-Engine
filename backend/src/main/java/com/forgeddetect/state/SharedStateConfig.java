package com.forgeddetect.state;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Picks the {@link SharedState} backend from {@code app.state.store} ({@code memory} | {@code redis}). */
@Configuration
public class SharedStateConfig {

    private static final Logger log = LoggerFactory.getLogger(SharedStateConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.state.store", havingValue = "redis")
    public SharedState redisSharedState(
            RedisConnectionFactory connections, @Value("${app.state.key-prefix:fmd:}") String prefix) {
        log.info("Shared state: Redis (rate limits, PIN lockouts and exposure counters are shared across instances)");
        return new RedisSharedState(new StringRedisTemplate(connections), prefix);
    }

    @Bean
    @ConditionalOnProperty(name = "app.state.store", havingValue = "memory", matchIfMissing = true)
    public SharedState inMemorySharedState(Clock clock) {
        log.info("Shared state: in memory (single instance). Set STATE_STORE=redis to run more than one backend.");
        return new InMemorySharedState(clock);
    }
}
