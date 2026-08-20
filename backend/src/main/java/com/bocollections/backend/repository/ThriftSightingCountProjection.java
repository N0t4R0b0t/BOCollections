package com.bocollections.backend.repository;

/** Spring Data projection for batch-loading sighting counts per session. */
public interface ThriftSightingCountProjection {
    Long getSessionId();
    Long getCount();
}
