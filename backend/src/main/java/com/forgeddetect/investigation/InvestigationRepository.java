package com.forgeddetect.investigation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    Optional<Investigation> findByCaseNumber(String caseNumber);

    boolean existsByCaseNumber(String caseNumber);

    List<Investigation> findAllByOrderByOpenedAtDesc();

    List<Investigation> findByStatusOrderByOpenedAtDesc(InvestigationStatus status);

    List<Investigation> findByStatus(InvestigationStatus status);

    List<Investigation> findByIdIn(Collection<Long> ids);
}
