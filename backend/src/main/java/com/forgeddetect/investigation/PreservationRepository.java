package com.forgeddetect.investigation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreservationRepository extends JpaRepository<Preservation, Long> {

    List<Preservation> findByInvestigationIdOrderByPreservedAtAscIdAsc(Long investigationId);

    Optional<Preservation> findByPreservationId(String preservationId);

    boolean existsByPreservationId(String preservationId);

    long countByInvestigationId(Long investigationId);
}
