package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScanDraftMergeRequest {
    @NotNull
    private Long primaryDraftId;
    @NotNull
    private Long secondaryDraftId;
}
