package com.forgeddetect.repository;

import com.forgeddetect.model.Complaint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    Optional<Complaint> findByCaseNumber(String caseNumber);

    Optional<Complaint> findByAnalysisId(Long analysisId);

    boolean existsByCaseNumber(String caseNumber);
}
