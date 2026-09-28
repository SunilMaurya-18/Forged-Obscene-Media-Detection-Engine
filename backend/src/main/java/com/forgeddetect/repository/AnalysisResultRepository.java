package com.forgeddetect.repository;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.SlaStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    List<AnalysisResult> findByDecisionOrderByCreatedAtDesc(Decision decision);

    List<AnalysisResult> findTop50ByDecisionOrderByCreatedAtDesc(Decision decision);

    long countByDecision(Decision decision);

    @Query("select avg(a.forgeryScore) from AnalysisResult a")
    Double averageForgeryScore();

    @Query("select avg(a.explicitScore) from AnalysisResult a")
    Double averageExplicitScore();

    /** Rows of {@code [modelDecision, decision, count]} for items a moderator has decided. */
    @Query("select a.modelDecision, a.decision, count(a) from AnalysisResult a "
            + "where a.reviewedAt is not null and a.modelDecision is not null group by a.modelDecision, a.decision")
    List<Object[]> countReviewedByModelAndHumanDecision();

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

    List<AnalysisResult> findByComplaintTypeIsNotNullAndSlaRuleLabelIsNull();

    long countByApiClientId(Long apiClientId);

    long countByApiClientIdAndDecision(Long apiClientId, Decision decision);

    long countByApiClientIdAndCreatedAtAfter(Long apiClientId, Instant after);

    Optional<AnalysisResult> findByNoticeNumber(String noticeNumber);

    boolean existsByNoticeNumber(String noticeNumber);

    /** Proposed AUTO_FLAG overrides waiting for a second moderator, oldest first. */
    List<AnalysisResult> findByPendingDecisionIsNotNullOrderByPendingProposedAtAsc();

    /** Open senior escalations (any decision, including takedowns), oldest escalation first. */
    List<AnalysisResult> findBySeniorEscalatedAtIsNotNullAndSeniorResolvedAtIsNullAndPendingDecisionIsNullOrderBySeniorEscalatedAtAsc();

    List<AnalysisResult> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    List<AnalysisResult> findByComplaintReceivedAtGreaterThanEqualAndComplaintReceivedAtLessThan(Instant from, Instant to);
}
