package com.forgeddetect.repository;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.SlaStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    List<AnalysisResult> findByDecisionOrderByCreatedAtDesc(Decision decision);

    long countByDecision(Decision decision);

    @Query("select avg(a.forgeryScore) from AnalysisResult a")
    Double averageForgeryScore();

    @Query("select avg(a.explicitScore) from AnalysisResult a")
    Double averageExplicitScore();

    /** Open complaints, most urgent (nearest deadline) first. */
    List<AnalysisResult> findByComplaintTypeIsNotNullAndResolvedAtIsNullOrderBySlaDeadlineAsc();

    List<AnalysisResult> findBySlaStatusOrderBySlaDeadlineAsc(SlaStatus slaStatus);

    long countByComplaintTypeIsNotNullAndResolvedAtIsNullAndSlaStatus(SlaStatus slaStatus);

    boolean existsByComplaintTypeIsNotNull();

    Optional<AnalysisResult> findByProvenanceId(String provenanceId);

    Optional<AnalysisResult> findFirstByContentHashAndProvenanceIdIsNotNullOrderByCreatedAtAsc(String contentHash);

    Optional<AnalysisResult> findFirstByPhashAndProvenanceIdIsNotNullOrderByCreatedAtAsc(String phash);

    long countBySourceProvenanceId(String sourceProvenanceId);

    List<AnalysisResult> findByProvenanceIdIsNull();
}
