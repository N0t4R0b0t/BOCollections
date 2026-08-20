package com.bocollections.backend.service;

import com.bocollections.backend.dto.TasteProfile;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.User;
import com.bocollections.backend.repository.CollectionEntryFreshnessProjection;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for taste-profile caching (staleness detection) and the "interesting" scoring
 * floors. Uses a real ObjectMapper (plain JSON (de)serialization, no need to mock it) alongside
 * mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
class TasteProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private CollectionEntryRepository collectionEntryRepository;
    @Mock private ItemRepository itemRepository;

    private TasteProfileService service;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TasteProfileService(userRepository, collectionEntryRepository, itemRepository, new ObjectMapper());
    }

    private CollectionEntryFreshnessProjection freshness(long count, LocalDateTime maxUpdatedAt) {
        return new CollectionEntryFreshnessProjection() {
            public Long getCount() { return count; }
            public LocalDateTime getMaxUpdatedAt() { return maxUpdatedAt; }
        };
    }

    @Test
    void getOrCompute_usesCacheWhenCountAndTimestampStillMatch() throws Exception {
        LocalDateTime cachedAt = LocalDateTime.now().minusHours(1);
        TasteProfile cached = TasteProfile.builder()
                .totalItems(5).categoryCounts(Map.of("PRINT", 5L))
                .formatCounts(Map.of()).publisherCounts(Map.of()).decadeCounts(Map.of()).build();
        User user = User.builder().id(USER_ID)
                .tasteProfile(new ObjectMapper().writeValueAsString(cached))
                .tasteProfileUpdatedAt(cachedAt)
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(collectionEntryRepository.findFreshnessByUserId(USER_ID)).thenReturn(freshness(5, cachedAt.minusMinutes(30)));

        TasteProfile result = service.getOrCompute(USER_ID);

        assertThat(result.getTotalItems()).isEqualTo(5);
        verify(collectionEntryRepository, never()).findByUserId(any());
    }

    @Test
    void getOrCompute_recomputesWhenCountDropsEvenIfTimestampLooksFresh() throws Exception {
        // Deletion blind-spot: removing the most-recently-touched entry doesn't change any
        // remaining row's updatedAt, so a pure MAX(updatedAt) check would wrongly call this fresh.
        LocalDateTime cachedAt = LocalDateTime.now().minusHours(1);
        TasteProfile cached = TasteProfile.builder()
                .totalItems(5).categoryCounts(Map.of()).formatCounts(Map.of()).publisherCounts(Map.of()).decadeCounts(Map.of()).build();
        User user = User.builder().id(USER_ID)
                .tasteProfile(new ObjectMapper().writeValueAsString(cached))
                .tasteProfileUpdatedAt(cachedAt)
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // Live count is now 3 (two entries deleted), but maxUpdatedAt is still older than cachedAt.
        when(collectionEntryRepository.findFreshnessByUserId(USER_ID)).thenReturn(freshness(3, cachedAt.minusMinutes(30)));
        when(collectionEntryRepository.findByUserId(USER_ID)).thenReturn(List.of());

        TasteProfile result = service.getOrCompute(USER_ID);

        assertThat(result.getTotalItems()).isEqualTo(0); // recomputed from the (now empty) live data
        verify(collectionEntryRepository).findByUserId(USER_ID);
        verify(userRepository).save(user);
    }

    @Test
    void getOrCompute_computesFromScratchWhenNeverCachedBefore() {
        User user = User.builder().id(USER_ID).build(); // no tasteProfile, no tasteProfileUpdatedAt
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(collectionEntryRepository.findFreshnessByUserId(USER_ID)).thenReturn(freshness(2, LocalDateTime.now()));

        CollectionEntry e1 = CollectionEntry.builder().id(1L).itemId(10L).userId(USER_ID).build();
        CollectionEntry e2 = CollectionEntry.builder().id(2L).itemId(11L).userId(USER_ID).build();
        when(collectionEntryRepository.findByUserId(USER_ID)).thenReturn(List.of(e1, e2));

        Item book = Item.builder().id(10L).category(MediaCategory.PRINT).format("Book").publisher("Tor").releaseYear(1998).build();
        Item cd = Item.builder().id(11L).category(MediaCategory.AUDIO).format("CD").publisher("Sub Pop").releaseYear(2003).build();
        when(itemRepository.findAllById(Set.of(10L, 11L))).thenReturn(List.of(book, cd));

        TasteProfile result = service.getOrCompute(USER_ID);

        assertThat(result.getTotalItems()).isEqualTo(2);
        assertThat(result.getCategoryCounts()).containsEntry("PRINT", 1L).containsEntry("AUDIO", 1L);
        assertThat(result.getDecadeCounts()).containsEntry("1990s", 1L).containsEntry("2000s", 1L);
    }

    @Test
    void scoreInteresting_requiresAtLeastTwoScoredDimensions() {
        TasteProfile profile = TasteProfile.builder()
                .totalItems(50)
                .categoryCounts(Map.of("AUDIO", 40L))
                .formatCounts(Map.of()).publisherCounts(Map.of()).decadeCounts(Map.of())
                .build();

        // Only category is supplied (1 scored dimension) — even though it hits, one low-specificity
        // dimension isn't enough signal on its own.
        boolean interesting = service.scoreInteresting(profile, MediaCategory.AUDIO, null, null, null);

        assertThat(interesting).isFalse();
    }

    @Test
    void scoreInteresting_requiresMinimumCollectionSize() {
        TasteProfile tinyProfile = TasteProfile.builder()
                .totalItems(3) // below the floor
                .categoryCounts(Map.of("AUDIO", 3L))
                .formatCounts(Map.of("cd", 3L))
                .publisherCounts(Map.of()).decadeCounts(Map.of())
                .build();

        boolean interesting = service.scoreInteresting(tinyProfile, MediaCategory.AUDIO, "CD", null, null);

        assertThat(interesting).isFalse();
    }

    @Test
    void scoreInteresting_trueWhenEnoughDimensionsMatchOnASizableCollection() {
        TasteProfile profile = TasteProfile.builder()
                .totalItems(50)
                .categoryCounts(Map.of("AUDIO", 40L))
                .formatCounts(Map.of("vinyl lp", 30L))
                .publisherCounts(Map.of("sub pop", 5L))
                .decadeCounts(Map.of("1990s", 20L))
                .build();

        boolean interesting = service.scoreInteresting(profile, MediaCategory.AUDIO, "Vinyl LP", "Unknown Label", 1995);

        assertThat(interesting).isTrue(); // category+format+decade hit, publisher misses -> 3/4 >= 0.5
    }

    @Test
    void scoreInteresting_falseWhenNothingMatches() {
        TasteProfile profile = TasteProfile.builder()
                .totalItems(50)
                .categoryCounts(Map.of("PRINT", 50L))
                .formatCounts(Map.of("book", 50L))
                .publisherCounts(Map.of()).decadeCounts(Map.of())
                .build();

        boolean interesting = service.scoreInteresting(profile, MediaCategory.GAME, "Game Cartridge", null, null);

        assertThat(interesting).isFalse();
    }
}
