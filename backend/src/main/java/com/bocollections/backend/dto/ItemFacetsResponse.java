package com.bocollections.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Year range + format/genre lists actually present in the catalogue (optionally scoped to a
 * category) — powers the filter builder's year slider bounds and format/genre dropdowns so none
 * of them ever offer a value that would return zero results. */
@Data
@Builder
public class ItemFacetsResponse {
    private Integer minYear;
    private Integer maxYear;
    private List<String> formats;
    private List<String> genres;
}
