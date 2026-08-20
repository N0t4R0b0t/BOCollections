package com.bocollections.backend.dto.thrift;

import com.bocollections.backend.entity.Confidence;
import com.bocollections.backend.entity.MediaCategory;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ThriftClassifyRequest {
    @NotBlank
    private String title;
    private MediaCategory category;
    private String format;
    private String publisher;
    private Integer releaseYear;
    private Confidence confidence;

    /** Set when held-item mode already resolved a barcode match — enables the fast ownership path. */
    private Long existingItemId;
    private List<Long> ownedInCollections = List.of();

    /** Restrict cross-reference to these collections; empty means all user collections. */
    private List<Long> collectionIds = List.of();

    /** Optional reference photo of the held item. */
    private String imageBase64;
    private String imageMimeType;
}
