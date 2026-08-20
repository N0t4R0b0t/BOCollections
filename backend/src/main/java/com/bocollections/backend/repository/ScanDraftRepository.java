package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ScanDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ScanDraftRepository extends JpaRepository<ScanDraft, Long> {

    List<ScanDraft> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    Optional<ScanDraft> findByIdAndSessionId(Long id, Long sessionId);

    Optional<ScanDraft> findFirstBySessionIdAndBarcodeOrderByCreatedAtAsc(Long sessionId, String barcode);

    /** Batch count of drafts still needing attention (excludes already-approved) — one GROUP BY query instead of one COUNT per session. */
    @Query("SELECT d.sessionId AS sessionId, COUNT(d) AS count FROM ScanDraft d WHERE d.sessionId IN :sessionIds AND d.status <> 'APPROVED' GROUP BY d.sessionId")
    List<ScanDraftCountProjection> countsBySessionIds(@Param("sessionIds") Set<Long> sessionIds);
}
