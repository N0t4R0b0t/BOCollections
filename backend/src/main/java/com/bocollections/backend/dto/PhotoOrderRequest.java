package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Full list of photo IDs in the desired display order — see ItemController/ScanSessionController
 * reorderPhotos. Any existing photo not mentioned keeps its relative order, appended at the end,
 * rather than being dropped from the gallery. */
@Data
public class PhotoOrderRequest {
    @NotEmpty
    private List<Long> photoIds;
}
