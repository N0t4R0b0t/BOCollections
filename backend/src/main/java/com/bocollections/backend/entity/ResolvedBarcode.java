package com.bocollections.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Permanent cache of barcode -> resolved metadata (or a confirmed miss), shared across every
 * user and category. A barcode's identity doesn't depend on who scans it, so once any lookup
 * chain has resolved (or exhausted) a given barcode, every future scan of it is served from here
 * instead of re-hitting OpenLibrary/Discogs/MusicBrainz/UPCitemdb/TMDB again.
 */
@Entity
@Table(name = "resolved_barcodes", indexes = {
    @Index(name = "idx_resolved_barcodes_barcode", columnList = "barcode", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolvedBarcode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String barcode;

    @Column(nullable = false)
    private boolean found;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MediaCategory category;

    @Column(length = 50)
    private String format;

    private String title;

    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "release_year")
    private Integer releaseYear;

    private String publisher;

    @Column(name = "external_id")
    private String externalId;

    @Column(length = 20)
    private String source; // OPEN_LIBRARY, DISCOGS, MUSICBRAINZ, TMDB

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
