package com.bocollections.backend.dto.thrift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ThriftSightingPhotosRequest {
    @NotEmpty
    @Valid
    private List<ThriftSightingPhotoInput> photos;
}
