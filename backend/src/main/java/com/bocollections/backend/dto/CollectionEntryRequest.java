package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CollectionEntryRequest {
    @NotNull
    private Long itemId;
    private String condition;
    private String notes;
    private LocalDate acquisitionDate;
    private BigDecimal purchasePrice;
    private String location;
}
