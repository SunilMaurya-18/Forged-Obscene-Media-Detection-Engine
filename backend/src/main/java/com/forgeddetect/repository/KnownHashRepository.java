package com.forgeddetect.repository;

import com.forgeddetect.model.KnownHash;
import com.forgeddetect.model.KnownHashSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnownHashRepository extends JpaRepository<KnownHash, Long> {

    boolean existsByAlgorithmAndHash(String algorithm, String hash);

    List<KnownHash> findByAlgorithm(String algorithm);

    List<KnownHash> findByAlgorithmAndHashAndSourceAndNote(String algorithm, String hash, KnownHashSource source, String note);

    long countBySource(KnownHashSource source);
}
