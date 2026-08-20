package com.bocollections.backend.dto.thrift;

import com.bocollections.backend.entity.ScanSessionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ThriftSessionResponse {
    private Long id;
    private String location;
    private ScanSessionStatus status;
    private long sightingCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
