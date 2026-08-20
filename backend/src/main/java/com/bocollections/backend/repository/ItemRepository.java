package com.bocollections.backend.repository;

import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Item> {

    Optional<Item> findByBarcode(String barcode);

    List<Item> findAllByBarcode(String barcode);

    @Query("SELECT i FROM Item i WHERE i.barcode = :barcode AND i.id <> :excludeId")
    List<Item> findDuplicates(@Param("barcode") String barcode, @Param("excludeId") Long excludeId);

    Page<Item> findByCategory(MediaCategory category, Pageable pageable);

    /** Matches title/publisher as before, plus a raw-text search over the metadata JSON blob —
     * director, cast, distributor, catalog number, etc. all live there rather than as dedicated
     * columns (see CLAUDE.md's "Media model"), so "Universal" or "Bruce Willis" only turns up
     * results via this last clause. Cheap LIKE-on-JSON-text rather than structured JSON querying:
     * imprecise (can't target a specific field) but simple, and good enough for free-text search. */
    @Query("""
            SELECT i FROM Item i
            WHERE LOWER(i.title) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(i.publisher) LIKE LOWER(CONCAT('%', :q, '%'))
               OR (i.metadata IS NOT NULL AND LOWER(CAST(i.metadata AS string)) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Item> search(@Param("q") String query, Pageable pageable);

    /** Id-only counterpart of the metadata-text clause in search() above — used by the
     * Specification-based filter builder (ItemSpecifications/ItemService.search(criteria, ...)),
     * which composes dynamic AND/OR combinations that Criteria API can't cast jsonb-to-text for
     * (see ItemSpecifications' class doc). Resolving matching ids via this proven JPQL cast first,
     * then filtering `id IN (...)` in the Specification, sidesteps that entirely. */
    @Query("""
            SELECT i.id FROM Item i
            WHERE i.metadata IS NOT NULL AND LOWER(CAST(i.metadata AS string)) LIKE LOWER(CONCAT('%', :text, '%'))
            """)
    List<Long> findIdsByMetadataContaining(@Param("text") String text);

    /** Bounds for the catalogue filter builder's year slider — see ItemController.facets. Two
     * separate queries rather than one `:category IS NULL OR i.category = :category` — Postgres's
     * JDBC driver can't infer a bind parameter's type when its only appearance is an `IS NULL`
     * comparison (confirmed live: "could not determine data type of parameter $1"), which is
     * exactly what happens here whenever no category filter is passed. */
    @Query("SELECT MIN(i.releaseYear) AS minYear, MAX(i.releaseYear) AS maxYear FROM Item i WHERE i.releaseYear IS NOT NULL")
    YearRangeProjection findYearRange();

    @Query("SELECT MIN(i.releaseYear) AS minYear, MAX(i.releaseYear) AS maxYear FROM Item i " +
           "WHERE i.releaseYear IS NOT NULL AND i.category = :category")
    YearRangeProjection findYearRangeByCategory(@Param("category") MediaCategory category);

    /** Distinct formats actually present in the catalogue — populates the filter builder's format
     * dropdown (previously the full static FORMATS_BY_CATEGORY list regardless of what's actually
     * in the data, so it offered choices that always returned zero results). Same split-query
     * shape as findYearRange/findDistinctGenres above, for the same reason. */
    @Query("SELECT DISTINCT i.format FROM Item i WHERE i.format IS NOT NULL ORDER BY i.format")
    List<String> findDistinctFormats();

    @Query("SELECT DISTINCT i.format FROM Item i WHERE i.format IS NOT NULL AND i.category = :category ORDER BY i.format")
    List<String> findDistinctFormatsByCategory(@Param("category") MediaCategory category);

    /** Distinct genres actually present in the catalogue (from metadata.genres, a JSON array —
     * see CLAUDE.md's "Media model") — populates the filter builder's genre dropdown. Native
     * query: jsonb_array_elements_text has no JPQL equivalent. Category passed as its raw
     * enum-name string rather than a typed MediaCategory to avoid native-query enum-binding
     * ambiguity across Hibernate versions. Split into two queries rather than one
     * `:category IS NULL OR category = :category` for the same reason as findYearRange/
     * findYearRangeByCategory above — Hibernate binds each occurrence of a named parameter as
     * its own placeholder, so the `IS NULL`-only occurrence has no type context for Postgres to
     * infer from and the query fails outright ("could not determine data type of parameter $1")
     * whenever no category filter is passed. */
    @Query(value = """
            SELECT DISTINCT jsonb_array_elements_text(metadata -> 'genres') AS genre
            FROM items
            WHERE metadata IS NOT NULL
              AND jsonb_typeof(metadata -> 'genres') = 'array'
            ORDER BY genre
            """, nativeQuery = true)
    List<String> findDistinctGenres();

    @Query(value = """
            SELECT DISTINCT jsonb_array_elements_text(metadata -> 'genres') AS genre
            FROM items
            WHERE metadata IS NOT NULL
              AND jsonb_typeof(metadata -> 'genres') = 'array'
              AND category = :category
            ORDER BY genre
            """, nativeQuery = true)
    List<String> findDistinctGenresByCategory(@Param("category") String category);

    Optional<Item> findByExternalSourceAndExternalId(String externalSource, String externalId);

    /**
     * Finds items owned by a user (via CollectionEntry) whose title matches after normalizing
     * both sides (lowercase, punctuation/whitespace collapsed to single spaces) — the caller
     * must pass a title already normalized the same way (see ThriftService.normalizeTitle).
     * When collectionIds is non-empty, restricts to those collections; otherwise all user collections.
     */
    @Query("""
            SELECT DISTINCT i FROM Item i
            WHERE TRIM(LOWER(FUNCTION('regexp_replace', i.title, '[^a-zA-Z0-9]+', ' ', 'g'))) = :normalizedTitle
              AND EXISTS (
                SELECT ce FROM CollectionEntry ce
                WHERE ce.itemId = i.id
                  AND ce.userId = :userId
                  AND (:#{#collectionIds.isEmpty()} = true OR ce.collectionId IN :collectionIds)
              )
            """)
    List<Item> findOwnedByNormalizedTitle(
            @Param("userId") Long userId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("collectionIds") List<Long> collectionIds);
}
