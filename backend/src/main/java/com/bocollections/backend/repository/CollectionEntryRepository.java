package com.bocollections.backend.repository;

import com.bocollections.backend.entity.CollectionEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CollectionEntryRepository extends JpaRepository<CollectionEntry, Long> {

    Page<CollectionEntry> findByCollectionId(Long collectionId, Pageable pageable);

    /** Unpaged variant for exports — needs every entry in one pass, not a page at a time. */
    List<CollectionEntry> findByCollectionId(Long collectionId);

    /** Same title/publisher/metadata LIKE match as ItemRepository.search, scoped to one
     * collection — an empty q matches everything, so callers don't need a separate "no filter"
     * code path. */
    @Query("""
            SELECT e FROM CollectionEntry e
            WHERE e.collectionId = :collectionId
              AND EXISTS (
                SELECT i FROM Item i
                WHERE i.id = e.itemId
                  AND (LOWER(i.title) LIKE LOWER(CONCAT('%', :q, '%'))
                       OR LOWER(i.publisher) LIKE LOWER(CONCAT('%', :q, '%'))
                       OR (i.metadata IS NOT NULL AND LOWER(CAST(i.metadata AS string)) LIKE LOWER(CONCAT('%', :q, '%'))))
              )
            """)
    Page<CollectionEntry> searchByCollectionId(@Param("collectionId") Long collectionId, @Param("q") String q, Pageable pageable);

    Optional<CollectionEntry> findByCollectionIdAndItemId(Long collectionId, Long itemId);

    boolean existsByCollectionIdAndItemId(Long collectionId, Long itemId);

    /** Item ids in a collection, before it (and its entries) get deleted — see
     * CollectionService.delete's orphan cleanup. */
    @Query("SELECT e.itemId FROM CollectionEntry e WHERE e.collectionId = :collectionId")
    List<Long> findItemIdsByCollectionId(@Param("collectionId") Long collectionId);

    /** Items are shared catalogue data, not owned by a single collection (the same barcode match
     * can be referenced by several collections, even across users) — this is how
     * CollectionService.delete decides whether an item a deleted collection contained is now
     * truly orphaned (delete it too) or still referenced elsewhere (leave it alone). */
    boolean existsByItemId(Long itemId);

    List<CollectionEntry> findByUserId(Long userId);

    @Query("SELECT COUNT(e) FROM CollectionEntry e WHERE e.collectionId = :collectionId")
    long countByCollectionId(@Param("collectionId") Long collectionId);

    /** Batch count — one GROUP BY query instead of one COUNT per collection. */
    @Query("SELECT e.collectionId AS collectionId, COUNT(e) AS count FROM CollectionEntry e WHERE e.collectionId IN :ids GROUP BY e.collectionId")
    List<CollectionCountProjection> countsByCollectionIds(@Param("ids") Set<Long> ids);

    @Query("SELECT e FROM CollectionEntry e WHERE e.userId = :userId AND e.itemId = :itemId")
    List<CollectionEntry> findByUserIdAndItemId(@Param("userId") Long userId, @Param("itemId") Long itemId);

    /**
     * Cheap staleness check for the cached taste profile. Comparing count too (not just
     * MAX(updatedAt)) matters — deleting the most-recently-touched entry leaves every remaining
     * row's updatedAt untouched, so a pure MAX(updatedAt) check would miss that the collection
     * shrank.
     */
    @Query("SELECT COUNT(e) AS count, MAX(e.updatedAt) AS maxUpdatedAt FROM CollectionEntry e WHERE e.userId = :userId")
    CollectionEntryFreshnessProjection findFreshnessByUserId(@Param("userId") Long userId);
}
