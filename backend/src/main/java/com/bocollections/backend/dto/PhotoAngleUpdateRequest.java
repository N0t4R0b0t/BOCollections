package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Changes an already-saved photo's angle (REFERENCE/FRONT/BACK/SPINE/DISC) — the angle picker
 * that exists pre-upload (ScanCapturePage/AddPhotosPage) has no post-save equivalent otherwise.
 * Shared shape for both item and scan-draft photos — see ItemController/ScanSessionController. */
@Data
public class PhotoAngleUpdateRequest {
    @NotBlank
    private String angle;
}
