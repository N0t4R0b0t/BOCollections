package com.bocollections.backend.service;

import com.bocollections.backend.dto.ExtractRequest;
import com.bocollections.backend.dto.ExtractResponse;
import com.bocollections.backend.dto.ItemFacetsResponse;
import com.bocollections.backend.dto.ItemRequest;
import com.bocollections.backend.dto.ItemResponse;
import com.bocollections.backend.dto.ItemSearchCriteria;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.repository.YearRangeProjection;
import com.bocollections.backend.dto.ItemUpdateRequest;
import com.bocollections.backend.dto.ScanDraftPhotoRequest;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.ItemPhoto;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.ItemPhotoRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.ItemSpecifications;
import com.bocollections.backend.service.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final ItemPhotoRepository photoRepository;
    private final StorageService storageService;
    private final VisualScanService visualScanService;
    private final ObjectMapper objectMapper;

    // Allowlist rather than trusting a raw client-supplied field name for Sort.by(...).
    private static final Map<String, Sort> SORT_OPTIONS = Map.of(
            "TITLE_ASC", Sort.by(Sort.Direction.ASC, "title"),
            "TITLE_DESC", Sort.by(Sort.Direction.DESC, "title"),
            "YEAR_NEWEST", Sort.by(Sort.Direction.DESC, "releaseYear"),
            "YEAR_OLDEST", Sort.by(Sort.Direction.ASC, "releaseYear"),
            "RECENTLY_ADDED", Sort.by(Sort.Direction.DESC, "createdAt")
    );

    @Transactional(readOnly = true)
    public Page<ItemResponse> search(String query, Pageable pageable) {
        Page<Item> page = itemRepository.search(query, pageable);
        Map<Long, String> firstPhotoUrls = firstPhotoUrlByItemId(page.getContent().stream().map(Item::getId).toList());
        return page.map(item -> toResponse(item, firstPhotoUrls));
    }

    /** Catalogue filter+sort builder — category/format/year-range/genre on top of the existing
     * free-text search, composed via Specification since the combination is dynamic (any subset
     * of filters may be present). `genre` is best-effort — see ItemSpecifications' doc comment. */
    @Transactional(readOnly = true)
    public Page<ItemResponse> search(ItemSearchCriteria criteria, Pageable pageable) {
        // Resolved up front via the proven JPQL-cast id lookup — see ItemSpecifications' class doc
        // for why this can't just be a Criteria-API predicate directly.
        List<Long> metadataMatchIds = (criteria.q() == null || criteria.q().isBlank())
                ? List.of() : itemRepository.findIdsByMetadataContaining(criteria.q());
        List<Long> genreMatchIds = (criteria.genre() == null || criteria.genre().isBlank())
                ? List.of() : itemRepository.findIdsByMetadataContaining(criteria.genre());

        Specification<Item> spec = Specification.allOf(
                ItemSpecifications.q(criteria.q(), metadataMatchIds),
                ItemSpecifications.category(criteria.category()),
                ItemSpecifications.format(criteria.format()),
                ItemSpecifications.yearFrom(criteria.yearFrom()),
                ItemSpecifications.yearTo(criteria.yearTo()),
                ItemSpecifications.genre(criteria.genre(), genreMatchIds)
        );
        // Revenue (TMDB's boxOffice figure — see TmdbService) only ever lives inside the metadata
        // JSONB blob, not a real column, so it can't go through Sort.by(...)/the SORT_OPTIONS
        // allowlist below the same way — same class of jsonb-vs-Criteria-API limitation the class
        // doc already explains for genre/q. Sorted in Java instead: fine at this app's scale (a
        // personal collection, not a web-scale catalogue), same tradeoff already accepted for the
        // LIKE-based metadata text search above.
        if ("REVENUE_HIGHEST".equals(criteria.sort())) {
            return searchSortedByRevenue(spec, pageable);
        }

        // Map.of(...) throws NPE on getOrDefault(null, ...) — unlike HashMap it doesn't special-case
        // a null key, it just calls null.hashCode() while probing. criteria.sort() is legitimately
        // null whenever the caller doesn't specify one, so this has to be guarded explicitly.
        Sort sort = criteria.sort() == null ? Sort.unsorted() : SORT_OPTIONS.getOrDefault(criteria.sort(), Sort.unsorted());
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        Page<Item> page = itemRepository.findAll(spec, sorted);
        Map<Long, String> firstPhotoUrls = firstPhotoUrlByItemId(page.getContent().stream().map(Item::getId).toList());
        return page.map(item -> toResponse(item, firstPhotoUrls));
    }

    private Page<ItemResponse> searchSortedByRevenue(Specification<Item> spec, Pageable pageable) {
        List<Item> matches = itemRepository.findAll(spec, Sort.unsorted());
        List<Item> sortedDesc = matches.stream()
                .sorted(Comparator.comparingLong((Item i) -> boxOfficeOf(i.getMetadata())).reversed())
                .toList();
        int start = Math.min((int) pageable.getOffset(), sortedDesc.size());
        int end = Math.min(start + pageable.getPageSize(), sortedDesc.size());
        List<Item> pageContent = sortedDesc.subList(start, end);
        Map<Long, String> firstPhotoUrls = firstPhotoUrlByItemId(pageContent.stream().map(Item::getId).toList());
        return new PageImpl<>(pageContent.stream().map(item -> toResponse(item, firstPhotoUrls)).toList(), pageable, sortedDesc.size());
    }

    private long boxOfficeOf(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return -1;
        try {
            return objectMapper.readTree(metadataJson).path("boxOffice").asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    @Transactional(readOnly = true)
    public ItemFacetsResponse getFacets(MediaCategory category) {
        YearRangeProjection yearRange = category != null
                ? itemRepository.findYearRangeByCategory(category)
                : itemRepository.findYearRange();
        List<String> formats = category != null
                ? itemRepository.findDistinctFormatsByCategory(category)
                : itemRepository.findDistinctFormats();
        List<String> genres = category != null
                ? itemRepository.findDistinctGenresByCategory(category.name())
                : itemRepository.findDistinctGenres();
        return ItemFacetsResponse.builder()
                .minYear(yearRange.getMinYear())
                .maxYear(yearRange.getMaxYear())
                .formats(formats)
                .genres(genres)
                .build();
    }

    @Transactional(readOnly = true)
    public ItemResponse getById(Long id) {
        return toResponse(findOrThrow(id), true);
    }

    @Transactional(readOnly = true)
    public ItemResponse getByBarcode(String barcode) {
        Item item = itemRepository.findByBarcode(barcode)
                .orElseThrow(() -> new NotFoundException("Item not found for barcode: " + barcode));
        return toResponse(item, true);
    }

    @Transactional
    public ItemResponse create(ItemRequest req) {
        Item item = fromRequest(req, new Item());
        return toResponse(itemRepository.save(item), true);
    }

    @Transactional
    public ItemResponse update(Long id, ItemRequest req) {
        Item item = findOrThrow(id);
        fromRequest(req, item);
        return toResponse(itemRepository.save(item), true);
    }

    /** Partial update — only touches fields the caller actually sent (see ItemUpdateRequest),
     * unlike update() above which always replaces the whole item. Needed for anything that only
     * wants to change one or two fields (picking a different gallery photo as the cover, applying
     * an AI re-extraction suggestion) — sending those through the full-replace PUT would silently
     * null out every field the caller didn't happen to include. */
    @Transactional
    public ItemResponse patch(Long id, ItemUpdateRequest req) {
        Item item = findOrThrow(id);
        if (req.getBarcode() != null) item.setBarcode(req.getBarcode());
        if (req.getBarcodeType() != null) item.setBarcodeType(req.getBarcodeType());
        if (req.getCategory() != null) item.setCategory(req.getCategory());
        if (req.getFormat() != null) item.setFormat(req.getFormat());
        if (req.getTitle() != null) item.setTitle(req.getTitle());
        if (req.getSubtitle() != null) item.setSubtitle(req.getSubtitle());
        if (req.getDescription() != null) item.setDescription(req.getDescription());
        if (req.getCoverUrl() != null) item.setCoverUrl(req.getCoverUrl());
        if (req.getReleaseYear() != null) item.setReleaseYear(req.getReleaseYear());
        if (req.getPublisher() != null) item.setPublisher(req.getPublisher());
        if (req.getMetadata() != null) item.setMetadata(req.getMetadata());
        return toResponse(itemRepository.save(item), true);
    }

    @Transactional
    public void delete(Long id) {
        if (!itemRepository.existsById(id)) {
            throw new NotFoundException("Item not found: " + id);
        }
        // The item_photos row cascades at the DB level, but nothing else removes the underlying
        // file — clean those up first, same as ScanSessionService does for draft photos.
        photoRepository.findByItemIdOrderBySortOrderAscIdAsc(id).forEach(p -> storageService.delete(p.getStorageKey()));
        itemRepository.deleteById(id);
    }

    /** Appends newly captured photos to an item's permanent gallery — unlike scan drafts, these
     * survive indefinitely, so a later visit can add more evidence or re-run vision against the
     * accumulated set rather than starting over. */
    @Transactional
    public ItemResponse addPhotos(Long itemId, List<ScanDraftPhotoRequest> photos) {
        Item item = findOrThrow(itemId);
        // sortOrder defaults to 0 on a new entity — without this, every newly added photo would
        // tie with (and sort before, on id-ascending tiebreak that already-existing photo don't
        // win) the gallery's very first photo instead of appending at the end.
        int[] nextOrder = { photoRepository.findByItemIdOrderBySortOrderAscIdAsc(itemId).stream()
                .mapToInt(ItemPhoto::getSortOrder).max().orElse(-1) + 1 };
        photos.forEach(p -> {
            byte[] bytes = Base64.getDecoder().decode(p.getImageBase64());
            String key = storageService.store(bytes, safeMimeType(p.getImageMimeType()));
            photoRepository.save(ItemPhoto.builder().itemId(item.getId()).storageKey(key).angle(p.getAngle()).sortOrder(nextOrder[0]++).build());
        });
        return toResponse(item, true);
    }

    @Transactional
    public ItemResponse deletePhoto(Long itemId, Long photoId) {
        Item item = findOrThrow(itemId);
        ItemPhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new NotFoundException("Photo not found: " + photoId));
        if (!photo.getItemId().equals(itemId)) {
            throw new NotFoundException("Photo not found: " + photoId);
        }
        storageService.delete(photo.getStorageKey());
        photoRepository.delete(photo);
        return toResponse(item, true);
    }

    @Transactional
    public ItemResponse updatePhotoAngle(Long itemId, Long photoId, String angle) {
        Item item = findOrThrow(itemId);
        ItemPhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new NotFoundException("Photo not found: " + photoId));
        if (!photo.getItemId().equals(itemId)) {
            throw new NotFoundException("Photo not found: " + photoId);
        }
        photo.setAngle(angle);
        photoRepository.save(photo);
        return toResponse(item, true);
    }

    @Transactional
    public ItemResponse reorderPhotos(Long itemId, List<Long> photoIds) {
        Item item = findOrThrow(itemId);
        List<ItemPhoto> photos = photoRepository.findByItemIdOrderBySortOrderAscIdAsc(itemId);
        Map<Long, ItemPhoto> remaining = photos.stream().collect(java.util.stream.Collectors.toMap(ItemPhoto::getId, java.util.function.Function.identity()));

        int order = 0;
        for (Long id : photoIds) {
            ItemPhoto p = remaining.remove(id);
            if (p != null) {
                p.setSortOrder(order++);
                photoRepository.save(p);
            }
        }
        // Anything not mentioned in photoIds keeps its relative order, appended at the end,
        // rather than being silently dropped from the gallery.
        for (ItemPhoto p : photos) {
            if (remaining.containsKey(p.getId())) {
                p.setSortOrder(order++);
                photoRepository.save(p);
            }
        }
        return toResponse(item, true);
    }

    /** Re-runs AI vision against everything currently in the item's photo gallery (its own shots
     * only — REFERENCE angle entries are stock/online images of the same catalogue entry, not
     * necessarily this exact copy, and would mislead vision about edition-specific details like
     * a torn sleeve or a signed page). Read-only: returns suggestions for the caller to apply via
     * a normal update(), same review-before-apply pattern the capture flow already uses. */
    @Transactional(readOnly = true)
    public ExtractResponse reextract(Long itemId, String hint) {
        findOrThrow(itemId);
        List<ItemPhoto> photos = photoRepository.findByItemIdOrderBySortOrderAscIdAsc(itemId).stream()
                .filter(p -> !"REFERENCE".equals(p.getAngle()))
                .toList();
        if (photos.isEmpty()) {
            return ExtractResponse.builder().visionAvailable(false).notes("No photos to analyse yet — add some first.").build();
        }
        ExtractRequest req = new ExtractRequest();
        req.setImagesBase64(photos.stream().map(this::loadAsBase64).toList());
        req.setHint(hint);
        return visualScanService.extract(req);
    }

    private String loadAsBase64(ItemPhoto photo) {
        try {
            Resource resource = storageService.load(photo.getStorageKey());
            return Base64.getEncoder().encodeToString(resource.getInputStream().readAllBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String safeMimeType(String mime) {
        return visualScanService.parseMime(mime).toString();
    }

    private Item findOrThrow(Long id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Item not found: " + id));
    }

    private Item fromRequest(ItemRequest req, Item item) {
        item.setBarcode(req.getBarcode());
        item.setBarcodeType(req.getBarcodeType());
        item.setCategory(req.getCategory());
        item.setFormat(req.getFormat());
        item.setTitle(req.getTitle());
        item.setSubtitle(req.getSubtitle());
        item.setDescription(req.getDescription());
        item.setCoverUrl(req.getCoverUrl());
        item.setReleaseYear(req.getReleaseYear());
        item.setPublisher(req.getPublisher());
        item.setExternalId(req.getExternalId());
        item.setExternalSource(req.getExternalSource());
        item.setMetadata(req.getMetadata());
        return item;
    }

    /** Used from list contexts (search results, collection entries) — skips the photos query
     * entirely rather than firing one per row. */
    public ItemResponse toResponse(Item item) {
        return toResponse(item, false, null);
    }

    public ItemResponse toResponse(Item item, boolean includePhotos) {
        return toResponse(item, includePhotos, null);
    }

    /** List-context variant of {@link #toResponse(Item)} — same no-per-row-photos-query behavior,
     * but still falls back to a first-photo cover (from a batch-loaded map, see
     * {@link #firstPhotoUrlByItemId}) when the item has no coverUrl of its own. */
    public ItemResponse toResponse(Item item, Map<Long, String> firstPhotoUrlByItemId) {
        return toResponse(item, false, firstPhotoUrlByItemId == null ? null : firstPhotoUrlByItemId.get(item.getId()));
    }

    /** Batch-loads each item's first photo (lowest sortOrder, matching gallery order) in one
     * query, keyed by item id — lets list views fall back to a first-photo cover without firing
     * one photos query per row. */
    public Map<Long, String> firstPhotoUrlByItemId(java.util.Collection<Long> itemIds) {
        Set<Long> ids = itemIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return photoRepository.findByItemIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.toMap(ItemPhoto::getItemId, p -> storageService.publicUrl(p.getStorageKey()), (a, b) -> a));
    }

    private ItemResponse toResponse(Item item, boolean includePhotos, String fallbackCoverUrl) {
        List<ItemResponse.DuplicateHint> duplicates = List.of();
        if (item.getBarcode() != null && item.getId() != null) {
            duplicates = itemRepository.findDuplicates(item.getBarcode(), item.getId())
                    .stream()
                    .map(d -> ItemResponse.DuplicateHint.builder()
                            .id(d.getId())
                            .title(d.getTitle())
                            .format(d.getFormat())
                            .releaseYear(d.getReleaseYear())
                            .publisher(d.getPublisher())
                            .build())
                    .toList();
        }
        List<ItemResponse.Photo> photos = includePhotos
                ? photoRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId()).stream()
                        .map(p -> ItemResponse.Photo.builder()
                                .id(p.getId())
                                .url(storageService.publicUrl(p.getStorageKey()))
                                .angle(p.getAngle())
                                .build())
                        .toList()
                : null;

        // No cover picked — fall back to the first gallery photo (same fallback either way,
        // whether that's from the just-fetched `photos` list here or a batch-loaded map a list
        // caller passed in) rather than showing a blank placeholder when real photos exist.
        String coverUrl = item.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            if (photos != null && !photos.isEmpty()) coverUrl = photos.get(0).getUrl();
            else if (fallbackCoverUrl != null) coverUrl = fallbackCoverUrl;
        }

        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .barcodeType(item.getBarcodeType())
                .category(item.getCategory())
                .format(item.getFormat())
                .title(item.getTitle())
                .subtitle(item.getSubtitle())
                .description(item.getDescription())
                .coverUrl(coverUrl)
                .releaseYear(item.getReleaseYear())
                .publisher(item.getPublisher())
                .externalId(item.getExternalId())
                .externalSource(item.getExternalSource())
                .metadata(item.getMetadata())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .duplicates(duplicates)
                .photos(photos)
                .build();
    }
}
