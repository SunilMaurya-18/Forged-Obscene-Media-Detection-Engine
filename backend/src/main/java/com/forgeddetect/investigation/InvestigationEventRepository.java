package com.forgeddetect.investigation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestigationEventRepository extends JpaRepository<InvestigationEvent, Long> {

    List<InvestigationEvent> findByInvestigationIdOrderByOccurredAtAscIdAsc(Long investigationId);

    Optional<InvestigationEvent> findTopByInvestigationIdOrderByOccurredAtDescIdDesc(Long investigationId);

    /** Chain order; events without a sequence number (never sealed, or inserted by hand) come last. */
    @Query("select e from InvestigationEvent e where e.investigationId = :investigationId order by case when e.seq is null then 1 else 0 end, e.seq, e.id")
    List<InvestigationEvent> findChain(@Param("investigationId") Long investigationId);

    Optional<InvestigationEvent> findTopByInvestigationIdAndSeqIsNotNullOrderBySeqDesc(Long investigationId);

    @Query("select distinct e.investigationId from InvestigationEvent e where e.hash is null")
    List<Long> findUnsealedInvestigationIds();
}
