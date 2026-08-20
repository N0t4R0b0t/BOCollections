package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScanVerifyRequest {
    @NotBlank
    private String imageBase64;
    @NotBlank
    private String imageMimeType; // e.g. "image/jpeg"
    @NotNull
    private LookupResult lookupResult;
}
