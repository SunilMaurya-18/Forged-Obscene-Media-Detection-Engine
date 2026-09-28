package com.forgeddetect.service;

import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.model.ModerationAuditEvent;
import com.forgeddetect.repository.ModerationAuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 23: internal moderation log that names the moderator (assignments, decisions, reveals,
 * second reviews, appeal decisions). Moderator-only; the public provenance trail stays anonymous.
 */
@Service
public class ModerationAuditService {

    private static final int MAX_DETAIL = 512;
    private static final String DECIDED_PREFIX = "Set ";

    private final ModerationAuditEventRepository events;
    private final Clock clock;

    public ModerationAuditService(ModerationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void record(String actor, ModerationAction action, Long analysisId, Long appealId, String detail) {
        String text = detail != null && detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        events.save(new ModerationAuditEvent(clock.instant(), actor, action, analysisId, appealId, text));
    }

    @Transactional(readOnly = true)
    public List<ModerationAuditEvent> forItem(Long analysisId) {
        return events.findByAnalysisIdOrderByOccurredAtAscIdAsc(analysisId);
    }

    /**
     * Moderators who set or upheld the takedown on this item before {@code before}: anyone who decided
     * AUTO_FLAG, or rejected a proposed reversal at second review. None of them may decide an appeal
     * against that takedown.
     */
    @Transactional(readOnly = true)
    public Set<String> takedownOwners(Long analysisId, Instant before) {
        Set<String> out = new TreeSet<>();
        for (ModerationAuditEvent e : forItem(analysisId)) {
            if (before != null && e.getOccurredAt().isAfter(before)) {
                continue;
            }
            boolean setFlag = e.getAction() == ModerationAction.DECIDED
                    && e.getDetail() != null && e.getDetail().startsWith(DECIDED_PREFIX + "AUTO_FLAG");
            if (setFlag || e.getAction() == ModerationAction.SECOND_REVIEW_REJECTED) {
                out.add(e.getActor());
            }
        }
        return out;
    }

    /** Detail of a {@link ModerationAction#DECIDED} event: {@code "Set <DECISION>[: reason]"}. */
    public static String decidedDetail(Object decision, String reason) {
        return DECIDED_PREFIX + decision + (reason != null && !reason.isBlank() ? ": " + reason.trim() : "");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> view(Long analysisId) {
        return forItem(analysisId).stream().map(ModerationAuditService::view).toList();
    }

    public static Map<String, Object> view(ModerationAuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("occurredAt", e.getOccurredAt().toString());
        m.put("actor", e.getActor());
        m.put("action", e.getAction().name());
        m.put("appealId", e.getAppealId());
        m.put("detail", e.getDetail());
        return m;
    }
}
