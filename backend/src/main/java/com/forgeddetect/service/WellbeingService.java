package com.forgeddetect.service;

import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.state.SharedState;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Phase 23: per-moderator exposure counter. Each distinct item a moderator acts on or reveals counts
 * once per session; at {@code wellbeing.exposure-limit} a break of {@code break-duration} is enforced
 * (decisions, second reviews, appeal decisions, reveals and evidentiary reports are refused until it
 * ends), then the counter starts again. A "session" is per moderator account and ends after
 * {@code idle-reset} of inactivity or when a break ends; signing out does not reset it.
 *
 * <p>Phase 24: the session lives in {@link SharedState} as two expiring keys, so a break holds on
 * every backend instance. The item set expires after {@code idle-reset} without activity; when a
 * break starts, both keys are set to expire when it ends, which resets the session.
 */
@Service
public class WellbeingService {

    public record Status(
            int exposures, int limit, int remaining, boolean onBreak, Instant breakEndsAt, String supportUrl) {

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exposures", exposures);
            m.put("limit", limit);
            m.put("remaining", remaining);
            m.put("onBreak", onBreak);
            m.put("breakEndsAt", breakEndsAt != null ? breakEndsAt.toString() : null);
            m.put("supportUrl", supportUrl);
            return m;
        }
    }

    private final WellbeingProperties props;
    private final ModerationAuditService audit;
    private final Clock clock;
    private final SharedState state;

    public WellbeingService(WellbeingProperties props, ModerationAuditService audit, Clock clock, SharedState state) {
        this.props = props;
        this.audit = audit;
        this.clock = clock;
        this.state = state;
    }

    public Status status(String moderator) {
        return snapshot(moderator, breakEndsAt(moderator));
    }

    /** @throws ModerationException 423 {@code break_required} while the moderator's break is running */
    public void requireNotOnBreak(String moderator) {
        Status st = status(moderator);
        if (st.onBreak()) {
            throw new ModerationException(HttpStatus.LOCKED, "break_required",
                    "Exposure limit reached. Take a break before reviewing more items.",
                    Map.of("wellbeing", st.view()));
        }
    }

    /** Counts {@code itemId} once per session; starts the break when this reaches the limit. */
    public Status recordExposure(String moderator, Long itemId) {
        Instant breakUntil = breakEndsAt(moderator);
        if (breakUntil != null) {
            return snapshot(moderator, breakUntil);
        }
        SharedState.SetAdd added = state.addToSet(itemsKey(moderator), String.valueOf(itemId), props.getIdleReset());
        if (added.added() && added.size() >= props.getExposureLimit()) {
            Instant until = startBreakIfNone(moderator);
            if (until != null) {
                audit.record(moderator, ModerationAction.BREAK_STARTED, null, null,
                        "Exposure limit " + props.getExposureLimit() + " reached; break until " + until);
                return snapshot(moderator, until);
            }
            return status(moderator);
        }
        return snapshot(moderator, null);
    }

    /** A voluntary break: same enforcement as the automatic one. */
    public Status startBreak(String moderator) {
        Instant until = startBreakIfNone(moderator);
        if (until != null) {
            audit.record(moderator, ModerationAction.BREAK_STARTED, null, null, "Break taken; until " + until);
        }
        return status(moderator);
    }

    /** @return the break's end when this call started it; null when one was already running */
    private Instant startBreakIfNone(String moderator) {
        Instant until = clock.instant().plus(props.getBreakDuration());
        if (!state.setIfAbsent(breakKey(moderator), String.valueOf(until.toEpochMilli()), props.getBreakDuration())) {
            return null;
        }
        state.expire(itemsKey(moderator), props.getBreakDuration());
        return until;
    }

    private Instant breakEndsAt(String moderator) {
        return state.get(breakKey(moderator)).map(v -> Instant.ofEpochMilli(Long.parseLong(v))).orElse(null);
    }

    private Status snapshot(String moderator, Instant breakUntil) {
        int limit = props.getExposureLimit();
        int used = (int) Math.min(Integer.MAX_VALUE, state.setSize(itemsKey(moderator)));
        return new Status(used, limit, Math.max(0, limit - used), breakUntil != null, breakUntil, props.getSupportUrl());
    }

    private static String itemsKey(String moderator) {
        return "wellbeing:items:" + moderator;
    }

    private static String breakKey(String moderator) {
        return "wellbeing:break:" + moderator;
    }
}
