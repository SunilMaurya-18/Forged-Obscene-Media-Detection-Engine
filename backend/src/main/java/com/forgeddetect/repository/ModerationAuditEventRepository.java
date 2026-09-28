package com.forgeddetect.repository;

import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.model.ModerationAuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationAuditEventRepository extends JpaRepository<ModerationAuditEvent, Long> {

    List<ModerationAuditEvent> findByAnalysisIdOrderByOccurredAtAscIdAsc(Long analysisId);

    List<ModerationAuditEvent> findByActorAndActionOrderByOccurredAtDesc(String actor, ModerationAction action);
}
