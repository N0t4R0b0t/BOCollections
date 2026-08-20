package com.bocollections.backend.dto.export;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportEntry {
    private String condition;
    private String notes;
    private LocalDate acquisitionDate;
    private BigDecimal purchasePrice;
    private String location;
    private ExportItem item;
}
