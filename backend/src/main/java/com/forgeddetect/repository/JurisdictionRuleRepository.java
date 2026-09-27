package com.forgeddetect.repository;

import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.JurisdictionRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JurisdictionRuleRepository extends JpaRepository<JurisdictionRule, Long> {

    Optional<JurisdictionRule> findByJurisdictionAndComplaintType(String jurisdiction, ComplaintType complaintType);

    boolean existsByJurisdictionAndComplaintType(String jurisdiction, ComplaintType complaintType);

    List<JurisdictionRule> findAllByOrderByJurisdictionAscComplaintTypeAsc();
}
