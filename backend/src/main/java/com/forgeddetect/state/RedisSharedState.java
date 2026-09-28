package com.forgeddetect.state;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link SharedState} in Redis. Multi-step updates run as Lua scripts so they stay atomic when
 * several backend instances hit the same key. The token bucket reads Redis {@code TIME}, so every
 * instance refills against one clock.
 */
public class RedisSharedState implements SharedState {

    private static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * capacity / window)
            local allowed = 0
            if tokens >= 1 then
              allowed = 1
              if ARGV[3] == '1' then tokens = tokens - 1 end
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], window * 2)
            return {allowed, tostring(tokens)}
            """, List.class);

    private static final RedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private static final RedisScript<List> ADD_TO_SET = new DefaultRedisScript<>("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return {added, redis.call('SCARD', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisSharedState(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public Bucket takeToken(String key, int capacity, Duration window, boolean consume) {
        List<?> out = redis.execute(TOKEN_BUCKET, List.of(k(key)),
                String.valueOf(capacity), String.valueOf(window.toMillis()), consume ? "1" : "0");
        return new Bucket(asLong(out.get(0)) == 1, Double.parseDouble(String.valueOf(out.get(1))));
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long n = redis.execute(INCREMENT, List.of(k(key)), String.valueOf(millis(ttl)));
        return n == null ? 0 : n;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(k(key)));
    }

    @Override
    public Optional<Duration> timeToLive(String key) {
        Long ms = redis.getExpire(k(key), TimeUnit.MILLISECONDS);
        return ms == null || ms < 0 ? Optional.empty() : Optional.of(Duration.ofMillis(ms));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(k(key), value, Duration.ofMillis(millis(ttl))));
    }

    @Override
    public void delete(String key) {
        redis.delete(k(key));
    }

    @Override
    public SetAdd addToSet(String key, String member, Duration ttl) {
        List<?> out = redis.execute(ADD_TO_SET, List.of(k(key)), member, String.valueOf(millis(ttl)));
        return new SetAdd(asLong(out.get(0)) == 1, asLong(out.get(1)));
    }

    @Override
    public long setSize(String key) {
        Long size = redis.opsForSet().size(k(key));
        return size == null ? 0 : size;
    }

    @Override
    public void expire(String key, Duration ttl) {
        redis.expire(k(key), Duration.ofMillis(millis(ttl)));
    }

    @Override
    public String backend() {
        return "redis";
    }

    @Override
    public boolean distributed() {
        return true;
    }

    private String k(String key) {
        return prefix + key;
    }

    private static long millis(Duration ttl) {
        return Math.max(1, ttl.toMillis());
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }
}
