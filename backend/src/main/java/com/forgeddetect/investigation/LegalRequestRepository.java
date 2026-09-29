package com.forgeddetect.investigation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalRequestRepository extends JpaRepository<LegalRequest, Long> {

    List<LegalRequest> findAllByOrderByFiledAtDescIdDesc();

    List<LegalRequest> findByInvestigatorAndStatusOrderByFiledAtDescIdDesc(String investigator, LegalRequestStatus status);

    Optional<LegalRequest> findByRequestNumber(String requestNumber);

    boolean existsByRequestNumber(String requestNumber);

    @Query("select distinct r from LegalRequest r join r.caseIds c where c = :investigationId order by r.filedAt asc, r.id asc")
    List<LegalRequest> findCovering(@Param("investigationId") Long investigationId);
}
