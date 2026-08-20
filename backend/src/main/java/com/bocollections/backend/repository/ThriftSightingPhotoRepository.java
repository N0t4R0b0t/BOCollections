package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ThriftSightingPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ThriftSightingPhotoRepository extends JpaRepository<ThriftSightingPhoto, Long> {
    List<ThriftSightingPhoto> findBySightingId(Long sightingId);
    List<ThriftSightingPhoto> findBySightingIdIn(Collection<Long> sightingIds);
    /** A single shelf photo routinely contains many detected items (that's the whole point of
     * shelf mode) — one uploaded photo file gets ONE storage key but MANY ThriftSightingPhoto
     * rows (one per item detected in it, each with its own bbox), so storageKey is legitimately
     * non-unique here. `findByStorageKey`'s Optional<> (used to, before this) blew up with
     * IncorrectResultSizeDataAccessException the moment a shelf photo had more than one item —
     * confirmed live: 500 on every photo load from a 145-item shelf scan. Any row sharing this
     * storageKey belongs to the same physical upload (same session/user), so "first" is fine for
     * the ownership check in ThriftSessionService.assertSightingPhotoAccessible. */
    Optional<ThriftSightingPhoto> findFirstByStorageKey(String storageKey);
    boolean existsByStorageKey(String storageKey);
}
