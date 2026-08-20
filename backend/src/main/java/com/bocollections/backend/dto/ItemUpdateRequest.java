package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Data;

/**
 * Partial-update body for {@code PATCH /items/{id}} — mirrors {@link ScanDraftUpdateRequest}.
 * Every field is optional and only applied when present (see ItemService#patch); unlike the
 * full {@code PUT /items/{id}} (backed by {@link ItemRequest}, always a complete replace), this
 * is safe for callers that only ever want to touch one or two fields — e.g. picking a different
 * gallery photo as the cover, or applying an AI re-extraction suggestion — without risking
 * silently nulling out everything else the request happened not to include.
 */
@Data
public class ItemUpdateRequest {
    private String barcode;
    private String barcodeType;
    private MediaCategory category;
    private String format;
    private String title;
    private String subtitle;
    private String description;
    private String coverUrl;
    private Integer releaseYear;
    private String publisher;
    private String metadata;
}
