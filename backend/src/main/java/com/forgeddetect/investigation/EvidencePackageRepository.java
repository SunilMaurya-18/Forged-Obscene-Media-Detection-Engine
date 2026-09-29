package com.forgeddetect.investigation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidencePackageRepository extends JpaRepository<EvidencePackage, Long> {

    List<EvidencePackage> findByInvestigationIdOrderByIssuedAtAscIdAsc(Long investigationId);

    List<EvidencePackage> findByInvestigatorOrderByIssuedAtDescIdDesc(String investigator);

    Optional<EvidencePackage> findByPackageId(String packageId);

    boolean existsByPackageId(String packageId);
}
