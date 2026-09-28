package com.forgeddetect.investigation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SightingLinkRepository extends JpaRepository<SightingLink, Long> {

    List<SightingLink> findByInvestigationId(Long investigationId);

    List<SightingLink> findBySightingIdIn(Collection<Long> sightingIds);

    boolean existsByInvestigationIdAndSightingId(Long investigationId, Long sightingId);

    long countByInvestigationId(Long investigationId);
}
