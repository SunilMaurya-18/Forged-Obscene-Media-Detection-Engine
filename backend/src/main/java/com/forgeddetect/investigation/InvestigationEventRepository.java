package com.forgeddetect.investigation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationEventRepository extends JpaRepository<InvestigationEvent, Long> {

    List<InvestigationEvent> findByInvestigationIdOrderByOccurredAtAscIdAsc(Long investigationId);

    Optional<InvestigationEvent> findTopByInvestigationIdOrderByOccurredAtDescIdDesc(Long investigationId);
}
