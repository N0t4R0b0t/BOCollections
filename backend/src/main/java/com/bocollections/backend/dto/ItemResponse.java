package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ItemResponse {
    private Long id;
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
    private String externalId;
    private String externalSource;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<DuplicateHint> duplicates;
    // Permanent gallery — unlike a scan draft's photos (thrown away once approved except for
    // coverUrl), these persist so a later "add more photos" / "re-run AI vision" pass on an
    // already-owned item has real evidence to work from instead of starting from nothing.
    private List<Photo> photos;

    @Data
    @Builder
    public static class DuplicateHint {
        private Long id;
        private String title;
        private String format;
        private Integer releaseYear;
        private String publisher;
    }

    @Data
    @Builder
    public static class Photo {
        private Long id;
        private String url;
        private String angle;
    }
}
