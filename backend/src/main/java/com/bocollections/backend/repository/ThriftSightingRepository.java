package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ThriftSighting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ThriftSightingRepository extends JpaRepository<ThriftSighting, Long> {

    List<ThriftSighting> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    Optional<ThriftSighting> findBySessionIdAndNormalizedTitle(Long sessionId, String normalizedTitle);

    /** Batch count — one GROUP BY query instead of one COUNT per session. */
    @Query("SELECT s.sessionId AS sessionId, COUNT(s) AS count FROM ThriftSighting s WHERE s.sessionId IN :sessionIds GROUP BY s.sessionId")
    List<ThriftSightingCountProjection> countsBySessionIds(@Param("sessionIds") Set<Long> sessionIds);

    /** Plain LIKE search — mirrors ItemRepository.search()'s actual convention (not the unused GIN/tsvector index). */
    @Query("SELECT s FROM ThriftSighting s WHERE s.userId = :userId AND LOWER(s.title) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY s.lastSeenAt DESC")
    List<ThriftSighting> search(@Param("userId") Long userId, @Param("q") String query);
}
