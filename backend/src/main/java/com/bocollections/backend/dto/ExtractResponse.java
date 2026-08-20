package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExtractResponse {
    private MediaCategory category;
    private String format;
    private String title;
    private String subtitle;
    private String description;
    private String publisher;
    private Integer releaseYear;
    private String barcode;
    private String confidence; // HIGH, MEDIUM, LOW
    private String notes;      // anything the AI noticed (e.g. "spine partially obscured")
    // Everything else vision can read that no barcode database supplies — edition, disc count,
    // region, special features, credits, pressing details, etc. (see VisualScanService's prompt
    // for the full field set). A JSON blob rather than dozens of dedicated fields, same pattern
    // as LookupResult.metadata on the barcode side — keeps this extensible without a Java/TS
    // change every time one more field is worth capturing, and lets it flow straight into the
    // same "extra details" merge/display pipeline the barcode sources already use.
    private String metadata;
    @Builder.Default
    private boolean visionAvailable = true; // false only when every configured Ollama endpoint failed
}
