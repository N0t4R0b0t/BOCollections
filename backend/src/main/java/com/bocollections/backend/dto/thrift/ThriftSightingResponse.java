package com.bocollections.backend.dto.thrift;

import com.bocollections.backend.entity.Confidence;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.OwnedStatus;
import com.bocollections.backend.entity.ThriftSourceMode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ThriftSightingResponse {
    private Long id;
    private Long sessionId;
    private String title;
    private MediaCategory category;
    private String format;
    private String artistOrAuthor;
    private String publisher;
    private Integer releaseYear;
    private OwnedStatus ownedStatus;
    private Long itemId;
    private Confidence confidence;
    private List<Photo> photos;
    private ThriftSourceMode sourceMode;
    private Integer timesSeen;
    private Double matchScore;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;

    @Data
    @Builder
    public static class Photo {
        private Long id;
        private String url;
        /** Only set for shelf-mode detections — see ThriftSightingPhoto's bbox columns. */
        private Double bboxX;
        private Double bboxY;
        private Double bboxW;
        private Double bboxH;
    }
}
