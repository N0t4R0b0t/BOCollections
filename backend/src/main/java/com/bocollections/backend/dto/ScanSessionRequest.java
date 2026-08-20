package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScanSessionRequest {
    @NotNull
    private Long collectionId;
}
