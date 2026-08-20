package com.bocollections.backend.repository;

import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/**
 * Composable filter predicates for the catalogue's dynamic filter+sort builder. `q`/`genre`
 * match against the metadata JSON blob, which needs an actual SQL CAST to text before Postgres
 * will run LOWER()/LIKE on it (jsonb has no lower() overload — confirmed live). Criteria API's
 * `path.as(String.class)` only changes the JPA-side Java type, it does NOT emit a SQL CAST, so
 * that path fails at the DB. Rather than fight Hibernate's Criteria-level cast API, this reuses
 * the JPQL CAST that ItemRepository.search() already has proven working: ItemService resolves
 * matching IDs via a small id-only query first, then these specs just do `id IN (...)` — which
 * Criteria API handles natively (including the empty-set case, translated to an always-false
 * predicate) with no casting involved at all.
 */
public final class ItemSpecifications {

    private ItemSpecifications() {
    }

    // Each returns Specification.unrestricted() (a no-op contributing nothing to the final AND)
    // rather than a bare null when the criterion is absent — Specification.allOf/and() asserts
    // its argument is non-null, so a plain `null` here would throw when composed in ItemService.

    public static Specification<Item> category(MediaCategory category) {
        if (category == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<Item> format(String format) {
        if (format == null || format.isBlank()) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("format"), format);
    }

    public static Specification<Item> yearFrom(Integer yearFrom) {
        if (yearFrom == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("releaseYear"), yearFrom);
    }

    public static Specification<Item> yearTo(Integer yearTo) {
        if (yearTo == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("releaseYear"), yearTo);
    }

    /** q matches title/publisher directly (plain columns, no casting needed) OR an id already
     * known to match somewhere in the metadata blob (see class doc). */
    public static Specification<Item> q(String q, Collection<Long> metadataMatchIds) {
        if (q == null || q.isBlank()) return Specification.unrestricted();
        String like = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), like),
                cb.like(cb.lower(root.get("publisher")), like),
                root.get("id").in(metadataMatchIds));
    }

    public static Specification<Item> genre(String genre, Collection<Long> genreMatchIds) {
        if (genre == null || genre.isBlank()) return Specification.unrestricted();
        return (root, query, cb) -> root.get("id").in(genreMatchIds);
    }
}
