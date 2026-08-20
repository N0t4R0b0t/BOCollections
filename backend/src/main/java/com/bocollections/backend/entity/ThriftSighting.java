package com.bocollections.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "thrift_sightings", indexes = {
    @Index(name = "idx_thrift_sightings_session_id", columnList = "session_id"),
    @Index(name = "idx_thrift_sightings_user_id_normalized_title", columnList = "user_id, normalized_title")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThriftSighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    // Denormalized (same precedent as CollectionEntry.userId) so cross-session search doesn't
    // need a join through thrift_sessions on a hot per-user query.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "normalized_title", nullable = false, length = 500)
    private String normalizedTitle;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MediaCategory category;

    @Column(length = 50)
    private String format;

    @Column(name = "artist_or_author", length = 255)
    private String artistOrAuthor;

    @Column(length = 255)
    private String publisher;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "owned_status", nullable = false, length = 20)
    private OwnedStatus ownedStatus;

    @Column(name = "item_id")
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Confidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_mode", length = 10)
    private ThriftSourceMode sourceMode;

    // Collection-relevance ranking for shelf-mode's batch-analyze results list (see
    // TasteProfileService.score) — null for sightings never touched by an analyze pass.
    @Column(name = "match_score")
    private Double matchScore;

    @Column(name = "times_seen", nullable = false)
    @Builder.Default
    private Integer timesSeen = 1;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (lastSeenAt == null) lastSeenAt = createdAt;
    }
}
