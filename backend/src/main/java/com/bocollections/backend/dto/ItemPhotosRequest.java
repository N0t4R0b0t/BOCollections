package com.bocollections.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ItemPhotosRequest {
    @NotEmpty
    @Valid
    private List<ScanDraftPhotoRequest> photos;
}
