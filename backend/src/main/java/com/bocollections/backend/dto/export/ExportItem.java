package com.bocollections.backend.dto.export;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportItem {
    private String barcode;
    private String barcodeType;
    private String category;
    private String format;
    private String title;
    private String subtitle;
    private String description;
    private String coverUrl;
    private Integer releaseYear;
    private String publisher;
    private String externalId;
    private String externalSource;
    private String metadata;
    private List<ExportPhoto> photos;
}
