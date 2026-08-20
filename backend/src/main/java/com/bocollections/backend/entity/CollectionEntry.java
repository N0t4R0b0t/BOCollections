package com.bocollections.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "collection_entries", indexes = {
    @Index(name = "idx_entries_collection_id", columnList = "collection_id"),
    @Index(name = "idx_entries_item_id", columnList = "item_id"),
    @Index(name = "idx_entries_user_id", columnList = "user_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_entries_collection_item", columnNames = {"collection_id", "item_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollectionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "collection_id", nullable = false)
    private Long collectionId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 15)
    @Builder.Default
    private String condition = "UNKNOWN"; // MINT, NEAR_MINT, VERY_GOOD, GOOD, FAIR, POOR, UNKNOWN

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "acquisition_date")
    private LocalDate acquisitionDate;

    @Column(name = "purchase_price", precision = 10, scale = 2)
    private BigDecimal purchasePrice;

    @Column(length = 100)
    private String location; // physical shelf / box location

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
