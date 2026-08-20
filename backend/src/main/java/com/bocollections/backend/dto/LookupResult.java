package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LookupResult {
    private String barcode;
    private String source; // CATALOGUE, OPEN_LIBRARY, MUSICBRAINZ, DISCOGS, TMDB, NOT_FOUND
    private MediaCategory category;
    private String format;
    private String title;
    private String subtitle;
    private String description;
    private String coverUrl;
    private Integer releaseYear;
    private String publisher;
    private String externalId;
    private String metadata;

    // Set if this barcode already exists in our item catalogue
    private Long existingItemId;
    // Collection IDs (for the authenticated user) that already own this item
    private List<Long> ownedInCollections;
}
