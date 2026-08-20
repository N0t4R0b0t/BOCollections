package com.bocollections.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "scan_draft_photos", indexes = {
    @Index(name = "idx_scan_draft_photos_draft_id", columnList = "draft_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanDraftPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id", nullable = false)
    private Long draftId;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(nullable = false, length = 20)
    private String angle; // REFERENCE, FRONT, BACK, SPINE, DISC

    // Gallery display order — user-editable via the reorder endpoint, otherwise defaults to
    // append-at-end (see ScanSessionService.addPhotos).
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
