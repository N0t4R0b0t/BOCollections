package com.bocollections.backend.dto.thrift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Shelf mode's shoot-then-analyze batch — one or more shots taken this pass, all sent together
 * rather than the old per-photo auto-fire scan. See ThriftSessionService.analyzeShelf. */
@Data
public class ThriftShelfAnalyzeRequest {

    @NotEmpty
    @Valid
    private List<PhotoInput> photos;

    /** Restrict cross-reference to these collections; empty means all user collections. */
    private List<Long> collectionIds = List.of();

    @Data
    public static class PhotoInput {
        private String imageBase64;
        private String imageMimeType = "image/jpeg";
    }
}
