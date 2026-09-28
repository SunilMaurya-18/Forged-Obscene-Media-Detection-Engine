package com.forgeddetect.investigation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SightingRepository extends JpaRepository<Sighting, Long> {

    Optional<Sighting> findByDedupeKey(String dedupeKey);

    List<Sighting> findByAccountKeyIn(Collection<String> accountKeys);

    List<Sighting> findByProvenanceId(String provenanceId);

    List<Sighting> findByPhashIsNotNull();
}
