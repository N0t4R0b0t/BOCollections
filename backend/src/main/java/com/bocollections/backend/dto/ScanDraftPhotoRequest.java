package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ScanDraftPhotoRequest {
    @NotBlank
    private String imageBase64;
    @NotBlank
    private String imageMimeType;
    @NotBlank
    private String angle; // REFERENCE, FRONT, BACK, SPINE, DISC
}
