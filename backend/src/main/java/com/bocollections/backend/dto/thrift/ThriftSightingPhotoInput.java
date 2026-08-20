package com.bocollections.backend.dto.thrift;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ThriftSightingPhotoInput {
    @NotBlank
    private String imageBase64;
    @NotBlank
    private String imageMimeType;
}
