package com.bocollections.backend.dto.thrift;

import com.bocollections.backend.entity.Confidence;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.OwnedStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ThriftItem {

    private String title;
    private String artistOrAuthor;
    private MediaCategory category;
    private String format;
    private BoundingBox bbox;
    private Confidence confidence;
    private OwnedStatus ownedStatus;

    /** Non-null when ownedStatus is OWNED or DIFFERENT_VERSION. */
    private Long itemId;

    @Data
    @Builder
    public static class BoundingBox {
        private double x;
        private double y;
        private double w;
        private double h;
    }
}
