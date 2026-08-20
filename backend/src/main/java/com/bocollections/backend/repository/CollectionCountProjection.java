package com.bocollections.backend.repository;

/** Spring Data projection for batch-loading entry counts per collection. */
public interface CollectionCountProjection {
    Long getCollectionId();
    Long getCount();
}
