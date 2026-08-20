package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ScanDraftPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScanDraftPhotoRepository extends JpaRepository<ScanDraftPhoto, Long> {
    List<ScanDraftPhoto> findByDraftIdOrderBySortOrderAscIdAsc(Long draftId);
    // Grouped by draftId downstream (Collectors.groupingBy) — sorting here so each group comes
    // out in gallery order without a second per-group sort.
    List<ScanDraftPhoto> findByDraftIdInOrderBySortOrderAscIdAsc(List<Long> draftIds);
    Optional<ScanDraftPhoto> findByStorageKey(String storageKey);
}
