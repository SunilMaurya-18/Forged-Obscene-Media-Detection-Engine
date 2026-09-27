package com.forgeddetect.repository;

import com.forgeddetect.model.ApiClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    Optional<ApiClient> findByKeyHash(String keyHash);

    boolean existsByNameIgnoreCase(String name);

    List<ApiClient> findAllByOrderByCreatedAtDesc();

    /** Atomic, so concurrent requests on one key never lose a count. */
    @Modifying
    @Query("update ApiClient c set c.requestCount = c.requestCount + 1, c.lastUsedAt = :at where c.id = :id")
    int recordRequest(@Param("id") Long id, @Param("at") Instant at);

    @Modifying
    @Query("update ApiClient c set c.throttledCount = c.throttledCount + 1, c.lastUsedAt = :at where c.id = :id")
    int recordThrottled(@Param("id") Long id, @Param("at") Instant at);
}
