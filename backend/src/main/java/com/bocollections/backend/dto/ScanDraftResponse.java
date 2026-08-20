package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MatchKind;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.ScanDraftStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ScanDraftResponse {
    private Long id;
    private Long sessionId;
    private ScanDraftStatus status;
    private MatchKind matchKind;
    private Long existingItemId;
    private Long duplicateOfDraftId;
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
    private String confidence;
    private String externalSource;
    private List<Photo> photos;
    // Other items already in the user's catalogue with the same (normalized) title — e.g. you're
    // scanning a Blu-ray of a movie you already own on DVD. Distinct from duplicateOfDraftId
    // (same barcode scanned twice this session) and existingItemId (this exact item already
    // owned): this is "a different edition of the same thing", surfaced as information for the
    // user to decide on, never auto-blocked.
    private List<RelatedEdition> relatedEditions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class Photo {
        private Long id;
        private String url;
        private String angle;
    }

    @Data
    @Builder
    public static class RelatedEdition {
        private Long itemId;
        private String title;
        private String format;
        private Integer releaseYear;
    }
}
