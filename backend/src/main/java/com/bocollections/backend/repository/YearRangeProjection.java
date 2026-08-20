package com.bocollections.backend.repository;

/** Min/max releaseYear across items — bounds for the catalogue filter builder's year slider. */
public interface YearRangeProjection {
    Integer getMinYear();
    Integer getMaxYear();
}
