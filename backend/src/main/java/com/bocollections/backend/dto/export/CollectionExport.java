package com.bocollections.backend.dto.export;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/** Round-trip shape for both `GET /collections/{id}/export/json` and
 * `POST /collections/{id}/import/json` — importing a file this same endpoint just exported
 * (from this instance or a completely different one, since photos are embedded as base64 rather
 * than storage-key references) reconstructs every item, its extra-details metadata, and its full
 * photo gallery. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionExport {
    private String collectionName;
    private String description;
    private String primaryCategory;
    private String exportedAt;
    private List<ExportEntry> entries;
}
