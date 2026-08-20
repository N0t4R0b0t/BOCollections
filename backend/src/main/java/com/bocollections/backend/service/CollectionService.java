package com.bocollections.backend.service;

import com.bocollections.backend.dto.*;
import com.bocollections.backend.entity.Collection;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.exception.ConflictException;
import com.bocollections.backend.exception.ForbiddenException;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.CollectionCountProjection;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.CollectionRepository;
import com.bocollections.backend.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CollectionEntryRepository entryRepository;
    private final ItemRepository itemRepository;
    private final ItemService itemService;

    @Transactional(readOnly = true)
    public List<CollectionResponse> getCollections(Long userId) {
        List<Collection> collections = collectionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (collections.isEmpty()) return List.of();

        // Batch-load all counts in a single GROUP BY query instead of one COUNT per collection.
        Set<Long> ids = collections.stream().map(Collection::getId).collect(Collectors.toSet());
        Map<Long, Long> countMap = entryRepository.countsByCollectionIds(ids)
                .stream().collect(Collectors.toMap(
                        CollectionCountProjection::getCollectionId,
                        CollectionCountProjection::getCount));

        return collections.stream()
                .map(c -> toResponse(c, countMap.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CollectionResponse getCollection(Long id, Long userId) {
        Collection c = findOrThrow(id, userId);
        return toResponse(c, entryRepository.countByCollectionId(id));
    }

    @Transactional
    public CollectionResponse create(CollectionRequest req, Long userId) {
        Collection c = Collection.builder()
                .userId(userId)
                .name(req.getName())
                .description(req.getDescription())
                .primaryCategory(req.getPrimaryCategory())
                .build();
        c = collectionRepository.save(c);
        return toResponse(c, 0);
    }

    @Transactional
    public CollectionResponse update(Long id, CollectionRequest req, Long userId) {
        Collection c = findOrThrow(id, userId);
        c.setName(req.getName());
        c.setDescription(req.getDescription());
        c.setPrimaryCategory(req.getPrimaryCategory());
        return toResponse(collectionRepository.save(c), entryRepository.countByCollectionId(id));
    }

    /**
     * Deleting a collection also cleans up any item that was only ever in this collection —
     * without this, an item with its last collection membership removed just sits in the
     * catalogue with nothing pointing to it. Items are shared catalogue data though (the same
     * barcode match can be referenced by several collections, even across users — see
     * CollectionEntryRepository.existsByItemId's doc comment), so this only deletes an item if no
     * *other* collection entry anywhere still references it.
     */
    @Transactional
    public void delete(Long id, Long userId) {
        Collection c = findOrThrow(id, userId);
        List<Long> itemIds = entryRepository.findItemIdsByCollectionId(id);
        collectionRepository.delete(c);
        collectionRepository.flush(); // force the DB-level ON DELETE CASCADE before the orphan check below
        for (Long itemId : itemIds) {
            if (!entryRepository.existsByItemId(itemId)) {
                itemService.delete(itemId);
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<CollectionEntryResponse> getEntries(Long collectionId, Long userId, String q, Pageable pageable) {
        if (!collectionRepository.existsByIdAndUserId(collectionId, userId)) {
            throw new ForbiddenException("Collection not found");
        }
        Page<CollectionEntry> page = entryRepository.searchByCollectionId(collectionId, q == null ? "" : q, pageable);

        // Batch-load all items on this page in one query instead of one query per entry.
        Set<Long> itemIds = page.stream().map(CollectionEntry::getItemId).collect(Collectors.toSet());
        Map<Long, Item> itemMap = itemRepository.findAllById(itemIds)
                .stream().collect(Collectors.toMap(Item::getId, Function.identity()));
        Map<Long, String> firstPhotoUrls = itemService.firstPhotoUrlByItemId(itemIds);

        return page.map(e -> toEntryResponse(e, itemMap.get(e.getItemId()), firstPhotoUrls));
    }

    /**
     * Looks up an existing entry for this item in this collection without creating one — used by
     * bulk-scan-mode's "add anyway" recovery for a draft that was auto-skipped as already-owned,
     * so re-approving it doesn't just hit addEntry's duplicate-guard 409.
     */
    @Transactional(readOnly = true)
    public Optional<CollectionEntryResponse> findEntryForItem(Long collectionId, Long itemId, Long userId) {
        if (!collectionRepository.existsByIdAndUserId(collectionId, userId)) {
            throw new ForbiddenException("Collection not found");
        }
        return entryRepository.findByCollectionIdAndItemId(collectionId, itemId)
                .map(e -> toEntryResponse(e, itemRepository.findById(e.getItemId()).orElse(null)));
    }

    @Transactional
    public CollectionEntryResponse addEntry(Long collectionId, CollectionEntryRequest req, Long userId) {
        if (!collectionRepository.existsByIdAndUserId(collectionId, userId)) {
            throw new ForbiddenException("Collection not found");
        }
        if (entryRepository.existsByCollectionIdAndItemId(collectionId, req.getItemId())) {
            throw new ConflictException("Item already in this collection");
        }
        Item item = itemRepository.findById(req.getItemId())
                .orElseThrow(() -> new NotFoundException("Item not found: " + req.getItemId()));

        CollectionEntry entry = CollectionEntry.builder()
                .collectionId(collectionId)
                .itemId(req.getItemId())
                .userId(userId)
                .condition(req.getCondition() != null ? req.getCondition() : "UNKNOWN")
                .notes(req.getNotes())
                .acquisitionDate(req.getAcquisitionDate())
                .purchasePrice(req.getPurchasePrice())
                .location(req.getLocation())
                .build();
        entry = entryRepository.save(entry);
        return toEntryResponse(entry, item);
    }

    @Transactional
    public CollectionEntryResponse updateEntry(Long collectionId, Long entryId, CollectionEntryRequest req, Long userId) {
        if (!collectionRepository.existsByIdAndUserId(collectionId, userId)) {
            throw new ForbiddenException("Collection not found");
        }
        CollectionEntry entry = entryRepository.findById(entryId)
                .filter(e -> e.getCollectionId().equals(collectionId))
                .orElseThrow(() -> new NotFoundException("Entry not found: " + entryId));

        entry.setCondition(req.getCondition() != null ? req.getCondition() : entry.getCondition());
        entry.setNotes(req.getNotes());
        entry.setAcquisitionDate(req.getAcquisitionDate());
        entry.setPurchasePrice(req.getPurchasePrice());
        entry.setLocation(req.getLocation());
        entry = entryRepository.save(entry);

        Item item = itemRepository.findById(entry.getItemId()).orElse(null);
        return toEntryResponse(entry, item);
    }

    @Transactional
    public void removeEntry(Long collectionId, Long entryId, Long userId) {
        if (!collectionRepository.existsByIdAndUserId(collectionId, userId)) {
            throw new ForbiddenException("Collection not found");
        }
        CollectionEntry entry = entryRepository.findById(entryId)
                .filter(e -> e.getCollectionId().equals(collectionId))
                .orElseThrow(() -> new NotFoundException("Entry not found: " + entryId));
        entryRepository.delete(entry);
    }

    private Collection findOrThrow(Long id, Long userId) {
        return collectionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Collection not found: " + id));
    }

    private CollectionResponse toResponse(Collection c, long count) {
        return CollectionResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .primaryCategory(c.getPrimaryCategory())
                .itemCount(count)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private CollectionEntryResponse toEntryResponse(CollectionEntry e, Item item) {
        return toEntryResponse(e, item, null);
    }

    private CollectionEntryResponse toEntryResponse(CollectionEntry e, Item item, Map<Long, String> firstPhotoUrls) {
        return CollectionEntryResponse.builder()
                .id(e.getId())
                .collectionId(e.getCollectionId())
                .item(item != null ? (firstPhotoUrls != null ? itemService.toResponse(item, firstPhotoUrls) : itemService.toResponse(item)) : null)
                .condition(e.getCondition())
                .notes(e.getNotes())
                .acquisitionDate(e.getAcquisitionDate())
                .purchasePrice(e.getPurchasePrice())
                .location(e.getLocation())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
