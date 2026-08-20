package com.bocollections.backend.dto;

import com.bocollections.backend.entity.ScanSessionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScanSessionStatusRequest {
    @NotNull
    private ScanSessionStatus status;
}
