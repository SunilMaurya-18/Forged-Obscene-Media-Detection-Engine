package com.forgeddetect.state;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-instance {@link SharedState}. Expiry follows the injected clock, so tests can move time. */
public class InMemorySharedState implements SharedState {

    private static final int SWEEP_EVERY = 1024;

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();

    public InMemorySharedState(Clock clock) {
        this.clock = clock;
    }

    private static final class Entry {
        Object value;
        long expiresAt; // epoch millis; Long.MAX_VALUE = never

        Entry(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TokenState {
        double tokens;
        long updatedAt;
    }

    @Override
    public Bucket takeToken(String key, int capacity, Duration window, boolean consume) {
        long now = now();
        double perMilli = capacity / (double) window.toMillis();
        Bucket[] out = new Bucket[1];
        entries.compute(key, (k, e) -> {
            Entry live = alive(e, now) ? e : null;
            TokenState s;
            if (live == null) {
                s = new TokenState();
                s.tokens = capacity;
                s.updatedAt = now;
                live = new Entry(s, 0);
            } else {
                s = (TokenState) live.value;
            }
            s.tokens = Math.min(capacity, s.tokens + Math.max(0, now - s.updatedAt) * perMilli);
            s.updatedAt = now;
            boolean allowed = s.tokens >= 1;
            if (allowed && consume) {
                s.tokens -= 1;
            }
            live.expiresAt = now + window.toMillis() * 2;
            out[0] = new Bucket(allowed, s.tokens);
            return live;
        });
        maybeSweep(now);
        return out[0];
    }

    @Override
    public long increment(String key, Duration ttl) {
        long now = now();
        long[] out = new long[1];
        entries.compute(key, (k, e) -> {
            if (!alive(e, now)) {
                out[0] = 1;
                return new Entry(1L, expiry(now, ttl));
            }
            long next = Long.parseLong(String.valueOf(e.value)) + 1;
            e.value = next;
            out[0] = next;
            return e;
        });
        maybeSweep(now);
        return out[0];
    }

    @Override
    public Optional<String> get(String key) {
        Entry e = entries.get(key);
        return alive(e, now()) ? Optional.of(String.valueOf(e.value)) : Optional.empty();
    }

    @Override
    public Optional<Duration> timeToLive(String key) {
        long now = now();
        Entry e = entries.get(key);
        if (!alive(e, now) || e.expiresAt == Long.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(e.expiresAt - now));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long now = now();
        boolean[] set = new boolean[1];
        entries.compute(key, (k, e) -> {
            if (alive(e, now)) {
                return e;
            }
            set[0] = true;
            return new Entry(value, expiry(now, ttl));
        });
        maybeSweep(now);
        return set[0];
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SetAdd addToSet(String key, String member, Duration ttl) {
        long now = now();
        SetAdd[] out = new SetAdd[1];
        entries.compute(key, (k, e) -> {
            Entry live = alive(e, now) ? e : new Entry(new HashSet<String>(), 0);
            Set<String> members = (Set<String>) live.value;
            boolean added = members.add(member);
            live.expiresAt = expiry(now, ttl);
            out[0] = new SetAdd(added, members.size());
            return live;
        });
        maybeSweep(now);
        return out[0];
    }

    @Override
    public long setSize(String key) {
        long now = now();
        long[] size = new long[1];
        entries.computeIfPresent(key, (k, e) -> {
            if (!alive(e, now)) {
                return null;
            }
            size[0] = ((Set<?>) e.value).size();
            return e;
        });
        return size[0];
    }

    @Override
    public void expire(String key, Duration ttl) {
        long now = now();
        entries.computeIfPresent(key, (k, e) -> {
            if (!alive(e, now)) {
                return null;
            }
            e.expiresAt = expiry(now, ttl);
            return e;
        });
    }

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public boolean distributed() {
        return false;
    }

    private long now() {
        return clock.millis();
    }

    private static boolean alive(Entry e, long now) {
        return e != null && now < e.expiresAt;
    }

    private static long expiry(long now, Duration ttl) {
        return ttl == null ? Long.MAX_VALUE : now + Math.max(1, ttl.toMillis());
    }

    private void maybeSweep(long now) {
        if (writes.incrementAndGet() % SWEEP_EVERY == 0) {
            entries.entrySet().removeIf(en -> !alive(en.getValue(), now));
        }
    }
}
