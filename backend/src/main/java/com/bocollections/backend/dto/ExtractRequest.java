package com.bocollections.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ExtractRequest {
    @NotEmpty
    private List<String> imagesBase64; // front, back, spine, etc.
    private String imageMimeType;      // default image/jpeg
    private String hint;               // optional: "this is a CD" or "this is a book"
}
