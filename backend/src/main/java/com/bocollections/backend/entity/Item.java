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
@Table(name = "items", indexes = {
    @Index(name = "idx_items_barcode", columnList = "barcode"),
    @Index(name = "idx_items_category", columnList = "category"),
    @Index(name = "idx_items_external_source_id", columnList = "external_source, external_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String barcode;

    @Column(name = "barcode_type", length = 20)
    private String barcodeType; // ISBN13, ISBN10, UPC, EAN13, CATALOG_NUMBER

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MediaCategory category;

    @Column(nullable = false, length = 50)
    private String format; // "Book", "Vinyl LP", "VHS", "Game Cartridge", etc.

    @Column(nullable = false)
    private String title;

    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "release_year")
    private Integer releaseYear;

    private String publisher; // label / studio / publisher

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "external_source", length = 30)
    private String externalSource; // OPEN_LIBRARY, DISCOGS, TMDB, IGDB, MANUAL

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata; // category-specific extra fields

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
