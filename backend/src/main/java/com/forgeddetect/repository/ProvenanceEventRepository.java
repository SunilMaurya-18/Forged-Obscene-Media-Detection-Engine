package com.forgeddetect.repository;

import com.forgeddetect.model.ProvenanceEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvenanceEventRepository extends JpaRepository<ProvenanceEvent, Long> {

    List<ProvenanceEvent> findByProvenanceIdOrderByOccurredAtAscIdAsc(String provenanceId);
}
