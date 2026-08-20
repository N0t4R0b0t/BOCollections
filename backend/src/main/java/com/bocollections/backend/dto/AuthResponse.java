package com.bocollections.backend.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private Long userId;
    private String email;
    private String displayName;
    private String accessToken;
    private String refreshToken;
}
