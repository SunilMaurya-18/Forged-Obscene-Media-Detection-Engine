package com.forgeddetect.investigation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationItemRepository extends JpaRepository<InvestigationItem, Long> {

    List<InvestigationItem> findByInvestigationIdOrderByAddedAtAscIdAsc(Long investigationId);

    List<InvestigationItem> findByInvestigationIdIn(Collection<Long> investigationIds);

    List<InvestigationItem> findByAnalysisId(Long analysisId);

    boolean existsByInvestigationIdAndAnalysisId(Long investigationId, Long analysisId);

    long countByInvestigationId(Long investigationId);
}
