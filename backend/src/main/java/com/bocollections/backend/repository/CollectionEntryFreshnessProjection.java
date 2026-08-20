package com.bocollections.backend.repository;

import java.time.LocalDateTime;

/** Cheap staleness check for the cached taste profile — count + latest touch, no full row load. */
public interface CollectionEntryFreshnessProjection {
    Long getCount();
    LocalDateTime getMaxUpdatedAt();
}
