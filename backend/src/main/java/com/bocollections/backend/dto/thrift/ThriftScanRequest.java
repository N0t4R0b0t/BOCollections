package com.bocollections.backend.dto.thrift;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ThriftScanRequest {

    @NotBlank
    private String imageBase64;

    private String imageMimeType = "image/jpeg";

    /** Restrict cross-reference to these collections; empty means all user collections. */
    private List<Long> collectionIds = List.of();
}
