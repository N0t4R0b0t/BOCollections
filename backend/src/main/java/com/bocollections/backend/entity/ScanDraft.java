package com.bocollections.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "scan_drafts", indexes = {
    @Index(name = "idx_scan_drafts_session_id", columnList = "session_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ScanDraftStatus status = ScanDraftStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_kind", nullable = false, length = 20)
    private MatchKind matchKind;

    @Column(name = "existing_item_id")
    private Long existingItemId;

    @Column(name = "duplicate_of_draft_id")
    private Long duplicateOfDraftId;

    @Column(length = 64)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MediaCategory category;

    @Column(length = 50)
    private String format;

    @Column(length = 500)
    private String title;

    @Column(length = 500)
    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "release_year")
    private Integer releaseYear;

    private String publisher;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(length = 10)
    private String confidence; // HIGH, MEDIUM, LOW

    // The barcode lookup provider that actually resolved this draft (TMDB, OPEN_LIBRARY,
    // MUSICBRAINZ, DISCOGS — see LookupResult.source), carried onto the created item's
    // externalSource on approval. Null when no barcode lookup contributed (vision-only or truly
    // manual entry) — approveDraft() falls back to "MANUAL" in that case.
    @Column(name = "external_source", length = 50)
    private String externalSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
