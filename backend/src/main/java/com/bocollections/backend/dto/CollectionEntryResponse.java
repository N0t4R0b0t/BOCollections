package com.bocollections.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class CollectionEntryResponse {
    private Long id;
    private Long collectionId;
    private ItemResponse item;
    private String condition;
    private String notes;
    private LocalDate acquisitionDate;
    private BigDecimal purchasePrice;
    private String location;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
