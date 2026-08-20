package com.bocollections.backend.service;

import com.bocollections.backend.dto.TasteProfile;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.User;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.CollectionEntryFreshnessProjection;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Derives a taste profile from structured fields already in the data model (category, format,
 * publisher, release-decade) across a user's owned collection — used to flag not-owned thrift
 * items as "interesting" rather than just "not owned". Cached as JSONB on User and recomputed
 * lazily/synchronously when stale — deliberately no RabbitMQ/background job (see
 * docs/specs/thrifting-mode-revamp.md Non-goals).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TasteProfileService {

    // Below this collection size, "the profile" is statistical noise (e.g. a 1-item AUDIO
    // collection trivially "matches" 100% of AUDIO candidates) — always NOT_OWNED, never INTERESTING.
    private static final long MIN_COLLECTION_SIZE = 10;
    // Require at least this many of {category, format, publisher, decade} to be present on the
    // candidate before ever scoring it as interesting — a single low-specificity hit (e.g. just
    // "it's a CD") isn't enough signal on its own.
    private static final int MIN_SCORED_DIMENSIONS = 2;
    private static final double INTERESTING_THRESHOLD = 0.5;

    private final UserRepository userRepository;
    private final CollectionEntryRepository collectionEntryRepository;
    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TasteProfile getOrCompute(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        CollectionEntryFreshnessProjection freshness = collectionEntryRepository.findFreshnessByUserId(userId);
        TasteProfile cached = deserialize(user.getTasteProfile());

        if (cached != null && isFresh(user, cached, freshness)) {
            return cached;
        }

        TasteProfile computed = compute(userId);
        persist(user, computed);
        return computed;
    }

    public boolean scoreInteresting(TasteProfile profile, MediaCategory category, String format, String publisher, Integer releaseYear) {
        return score(profile, category, format, publisher, releaseYear) >= INTERESTING_THRESHOLD;
    }

    /**
     * Numeric collection-relevance score (0.0–1.0, or exactly 0 below the collection-size/scored-
     * dimension floor) — the hits/scored ratio scoreInteresting already computed, just exposed as
     * a raw value instead of a threshold decision. Used to rank shelf-mode's batch-analyze results
     * ("score = a match calculation for what is currently in the collection", per the feature ask)
     * rather than just flagging a boolean.
     */
    public double score(TasteProfile profile, MediaCategory category, String format, String publisher, Integer releaseYear) {
        if (profile == null || profile.getTotalItems() < MIN_COLLECTION_SIZE) return 0;

        int scored = 0;
        int hits = 0;

        if (category != null) {
            scored++;
            if (profile.getCategoryCounts().getOrDefault(category.name(), 0L) > 0) hits++;
        }
        if (format != null) {
            scored++;
            if (profile.getFormatCounts().getOrDefault(format.toLowerCase(), 0L) > 0) hits++;
        }
        if (publisher != null) {
            scored++;
            if (profile.getPublisherCounts().getOrDefault(publisher.toLowerCase(), 0L) > 0) hits++;
        }
        if (releaseYear != null) {
            scored++;
            if (profile.getDecadeCounts().getOrDefault(decadeKey(releaseYear), 0L) > 0) hits++;
        }

        if (scored < MIN_SCORED_DIMENSIONS) return 0;
        return (double) hits / scored;
    }

    /**
     * A pure MAX(updatedAt) check can't detect deletions — removing the most-recently-touched
     * entry leaves every remaining row's updatedAt untouched, so comparing the live count against
     * the cached profile's totalItems closes that gap.
     */
    private boolean isFresh(User user, TasteProfile cached, CollectionEntryFreshnessProjection freshness) {
        if (user.getTasteProfileUpdatedAt() == null) return false;

        long liveCount = freshness.getCount() == null ? 0 : freshness.getCount();
        if (cached.getTotalItems() != liveCount) return false;

        LocalDateTime liveMax = freshness.getMaxUpdatedAt();
        if (liveMax == null) return true; // no entries at all, counts already matched (both 0)
        return !liveMax.isAfter(user.getTasteProfileUpdatedAt());
    }

    private TasteProfile compute(Long userId) {
        List<CollectionEntry> entries = collectionEntryRepository.findByUserId(userId);
        if (entries.isEmpty()) {
            return TasteProfile.builder()
                    .totalItems(0)
                    .categoryCounts(Map.of())
                    .formatCounts(Map.of())
                    .publisherCounts(Map.of())
                    .decadeCounts(Map.of())
                    .build();
        }

        Set<Long> itemIds = entries.stream().map(CollectionEntry::getItemId).collect(Collectors.toSet());
        Map<Long, Item> itemMap = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));

        Map<String, Long> categoryCounts = new HashMap<>();
        Map<String, Long> formatCounts = new HashMap<>();
        Map<String, Long> publisherCounts = new HashMap<>();
        Map<String, Long> decadeCounts = new HashMap<>();

        for (CollectionEntry entry : entries) {
            Item item = itemMap.get(entry.getItemId());
            if (item == null) continue;
            if (item.getCategory() != null) categoryCounts.merge(item.getCategory().name(), 1L, Long::sum);
            if (item.getFormat() != null) formatCounts.merge(item.getFormat().toLowerCase(), 1L, Long::sum);
            if (item.getPublisher() != null) publisherCounts.merge(item.getPublisher().toLowerCase(), 1L, Long::sum);
            if (item.getReleaseYear() != null) decadeCounts.merge(decadeKey(item.getReleaseYear()), 1L, Long::sum);
        }

        return TasteProfile.builder()
                .totalItems(entries.size())
                .categoryCounts(categoryCounts)
                .formatCounts(formatCounts)
                .publisherCounts(publisherCounts)
                .decadeCounts(decadeCounts)
                .build();
    }

    private String decadeKey(int releaseYear) {
        return (releaseYear / 10 * 10) + "s";
    }

    private void persist(User user, TasteProfile profile) {
        try {
            user.setTasteProfile(objectMapper.writeValueAsString(profile));
            user.setTasteProfileUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        } catch (Exception e) {
            log.warn("Failed to cache taste profile for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private TasteProfile deserialize(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, TasteProfile.class);
        } catch (Exception e) {
            return null;
        }
    }
}
