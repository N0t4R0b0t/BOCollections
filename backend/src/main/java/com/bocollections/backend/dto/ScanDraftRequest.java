package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MatchKind;
import com.bocollections.backend.entity.MediaCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ScanDraftRequest {
    @NotNull
    private MatchKind matchKind;
    private Long existingItemId;
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
    /** The barcode lookup provider that resolved this draft (TMDB, OPEN_LIBRARY, MUSICBRAINZ,
     * DISCOGS — see LookupResult.source) — see ScanDraft.externalSource. */
    private String externalSource;
    @Valid
    private List<ScanDraftPhotoRequest> photos;
}
