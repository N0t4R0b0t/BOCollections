package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ItemPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ItemPhotoRepository extends JpaRepository<ItemPhoto, Long> {
    List<ItemPhoto> findByItemIdOrderBySortOrderAscIdAsc(Long itemId);
    /** Batch variant for list views — see ItemService.firstPhotoUrlByItemId. */
    List<ItemPhoto> findByItemIdInOrderBySortOrderAscIdAsc(Set<Long> itemIds);
    Optional<ItemPhoto> findByStorageKey(String storageKey);
    boolean existsByStorageKey(String storageKey);
}
