package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ItemRequest {
    private String barcode;
    private String barcodeType;
    @NotNull
    private MediaCategory category;
    @NotBlank
    private String format;
    @NotBlank
    private String title;
    private String subtitle;
    private String description;
    private String coverUrl;
    private Integer releaseYear;
    private String publisher;
    private String externalId;
    private String externalSource;
    private String metadata;
}
