package com.forgeddetect.repository;

import com.forgeddetect.model.Appeal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppealRepository extends JpaRepository<Appeal, Long> {

    Optional<Appeal> findByCaseNumber(String caseNumber);

    Optional<Appeal> findByAnalysisId(Long analysisId);

    List<Appeal> findByStatusOrderBySlaDeadlineAsc(Appeal.Status status);

    List<Appeal> findTop50ByStatusNotOrderByDecidedAtDesc(Appeal.Status status);

    List<Appeal> findByFiledAtGreaterThanEqualAndFiledAtLessThan(Instant from, Instant to);
}
