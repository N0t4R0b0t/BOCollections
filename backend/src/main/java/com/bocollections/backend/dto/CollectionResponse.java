package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CollectionResponse {
    private Long id;
    private String name;
    private String description;
    private MediaCategory primaryCategory;
    private long itemCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
