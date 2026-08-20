package com.bocollections.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "thrift_sighting_photos", indexes = {
    @Index(name = "idx_thrift_sighting_photos_sighting_id", columnList = "sighting_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThriftSightingPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sighting_id", nullable = false)
    private Long sightingId;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    // Null except for shelf-mode detections — the position within this specific photo where the
    // sighting was found (normalized 0-1, origin top-left, same convention as ThriftItem.bbox).
    @Column(name = "bbox_x")
    private Double bboxX;
    @Column(name = "bbox_y")
    private Double bboxY;
    @Column(name = "bbox_w")
    private Double bboxW;
    @Column(name = "bbox_h")
    private Double bboxH;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
