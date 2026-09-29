package com.forgeddetect.investigation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalHoldRepository extends JpaRepository<LegalHold, Long> {

    List<LegalHold> findByInvestigationIdOrderByRequestedAtAscIdAsc(Long investigationId);

    Optional<LegalHold> findByHoldNumber(String holdNumber);

    boolean existsByHoldNumber(String holdNumber);

    long countByInvestigationIdAndStatusNot(Long investigationId, LegalHoldStatus status);
}
