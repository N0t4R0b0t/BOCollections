package com.bocollections.backend.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/** JSON-serialized into User.tasteProfile — a snapshot of the structured-field distribution across a user's owned collection. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasteProfile {
    private long totalItems;
    private Map<String, Long> categoryCounts;   // MediaCategory name -> count
    private Map<String, Long> formatCounts;     // lowercased format -> count
    private Map<String, Long> publisherCounts;  // lowercased publisher -> count
    private Map<String, Long> decadeCounts;     // e.g. "1990s" -> count
}
