package com.bocollections.backend.repository;

/** Spring Data projection for batch-loading draft counts per session. */
public interface ScanDraftCountProjection {
    Long getSessionId();
    Long getCount();
}
