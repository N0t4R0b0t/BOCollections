package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CollectionRequest {
    @NotBlank @Size(min = 1, max = 100)
    private String name;
    @Size(max = 500)
    private String description;
    private MediaCategory primaryCategory;
}
