package com.bocollections.backend.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScanVerifyResponse {
    private Boolean matches;       // null = uncertain (vision unavailable)
    private String confidence;     // HIGH, MEDIUM, LOW
    private String reason;
    @Builder.Default
    private boolean visionAvailable = true; // false only when every configured Ollama endpoint failed
}
