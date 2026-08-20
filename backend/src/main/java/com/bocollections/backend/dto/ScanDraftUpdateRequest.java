package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Data;

@Data
public class ScanDraftUpdateRequest {
    private String barcode;
    private MediaCategory category;
    private String format;
    private String title;
    private String subtitle;
    private String description;
    private String coverUrl;
    private Integer releaseYear;
    private String publisher;
    private String metadata;
    private String externalSource;
}
