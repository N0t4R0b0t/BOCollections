package com.bocollections.backend.dto.thrift;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Data;

/** Lets a reviewer apply a reextract() suggestion (or a manual correction) to a sighting after
 * the fact — only non-null fields are applied, same partial-patch contract as ScanDraft's update. */
@Data
public class ThriftSightingUpdateRequest {
    private String title;
    private MediaCategory category;
    private String format;
    private String artistOrAuthor;
    private String publisher;
    private Integer releaseYear;
}
