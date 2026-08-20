package com.bocollections.backend.dto;

import com.bocollections.backend.entity.ScanSessionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ScanSessionResponse {
    private Long id;
    private Long collectionId;
    private String collectionName;
    private ScanSessionStatus status;
    private long pendingDraftCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
