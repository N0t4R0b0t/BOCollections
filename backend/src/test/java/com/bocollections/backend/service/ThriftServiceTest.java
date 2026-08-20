package com.bocollections.backend.service;

import com.bocollections.backend.dto.TasteProfile;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.OwnedStatus;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ThriftService.classifyItem — the shared ownership/interest decision used by
 * both shelf mode and held-item mode.
 */
@ExtendWith(MockitoExtension.class)
class ThriftServiceTest {

    @Mock private VisualScanService visualScanService;
    @Mock private ItemRepository itemRepository;
    @Mock private CollectionEntryRepository collectionEntryRepository;
    @Mock private TasteProfileService tasteProfileService;

    private ThriftService service;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new ThriftService(visualScanService, itemRepository, collectionEntryRepository, tasteProfileService, new ObjectMapper());
    }

    @Test
    void classifyItem_barcodeFastPath_ownedWhenInRequestedCollection() {
        when(itemRepository.findById(900L)).thenReturn(java.util.Optional.of(Item.builder().id(900L).title("Some Title").build()));
        when(collectionEntryRepository.findByUserIdAndItemId(USER_ID, 900L)).thenReturn(List.of(
                CollectionEntry.builder().collectionId(5L).build(), CollectionEntry.builder().collectionId(6L).build()));

        var result = service.classifyItem(
                "Some Title", MediaCategory.AUDIO, "CD", "Label", 2001,
                900L, List.of(5L, 6L), List.of(5L), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.OWNED);
        assertThat(result.itemId()).isEqualTo(900L);
        // Trusts the exact catalogue link once verified — never falls back to the fuzzy
        // findOwnedByNormalizedTitle path.
        verify(itemRepository, never()).findOwnedByNormalizedTitle(any(), any(), any());
    }

    @Test
    void classifyItem_barcodeFastPath_neverReturnsDifferentVersion() {
        // Barcode-confirmed but not owned in any requested collection -> falls through to
        // not-owned/interesting, never DIFFERENT_VERSION (that's structurally shelf-mode-only).
        when(itemRepository.findById(900L)).thenReturn(java.util.Optional.of(Item.builder().id(900L).title("Some Title").build()));
        when(collectionEntryRepository.findByUserIdAndItemId(USER_ID, 900L)).thenReturn(List.of(
                CollectionEntry.builder().collectionId(7L).build()));
        when(tasteProfileService.getOrCompute(USER_ID)).thenReturn(TasteProfile.builder().totalItems(0).build());
        when(tasteProfileService.scoreInteresting(any(), any(), any(), any(), any())).thenReturn(false);

        var result = service.classifyItem(
                "Some Title", MediaCategory.AUDIO, "CD", "Label", 2001,
                900L, List.of(7L), List.of(5L), USER_ID);

        assertThat(result.ownedStatus()).isIn(OwnedStatus.NOT_OWNED, OwnedStatus.INTERESTING);
        assertThat(result.itemId()).isNull();
    }

    @Test
    void classifyItem_barcodeFastPath_upgradesToInterestingViaTasteProfile() {
        when(itemRepository.findById(900L)).thenReturn(java.util.Optional.of(Item.builder().id(900L).title("Some Title").build()));
        when(collectionEntryRepository.findByUserIdAndItemId(USER_ID, 900L)).thenReturn(List.of());
        TasteProfile profile = TasteProfile.builder().totalItems(50).build();
        when(tasteProfileService.getOrCompute(USER_ID)).thenReturn(profile);
        when(tasteProfileService.scoreInteresting(profile, MediaCategory.AUDIO, "CD", "Label", 2001)).thenReturn(true);

        var result = service.classifyItem(
                "Some Title", MediaCategory.AUDIO, "CD", "Label", 2001,
                900L, List.of(), List.of(5L), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.INTERESTING);
    }

    @Test
    void classifyItem_barcodeClaim_fallsBackToTitleMatchWhenItemNotFound() {
        // existingItemId points at nothing real (e.g. stale client state) -> don't trust it,
        // fall back to the normal fuzzy title lookup instead of blindly returning OWNED.
        when(itemRepository.findById(900L)).thenReturn(java.util.Optional.empty());
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), anyString(), any())).thenReturn(List.of());
        when(tasteProfileService.getOrCompute(USER_ID)).thenReturn(TasteProfile.builder().totalItems(0).build());
        when(tasteProfileService.scoreInteresting(any(), any(), any(), any(), any())).thenReturn(false);

        var result = service.classifyItem(
                "Some Title", MediaCategory.AUDIO, "CD", "Label", 2001,
                900L, List.of(5L), List.of(5L), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.NOT_OWNED);
        verifyNoInteractions(collectionEntryRepository);
    }

    @Test
    void classifyItem_barcodeClaim_fallsBackToTitleMatchWhenTitleDoesNotCorrespond() {
        // existingItemId resolves to a real item, but its title has nothing to do with what was
        // actually identified -> the claim is untrustworthy, fall back instead of trusting it.
        when(itemRepository.findById(900L)).thenReturn(java.util.Optional.of(Item.builder().id(900L).title("Completely Different Thing").build()));
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), anyString(), any())).thenReturn(List.of());
        when(tasteProfileService.getOrCompute(USER_ID)).thenReturn(TasteProfile.builder().totalItems(0).build());
        when(tasteProfileService.scoreInteresting(any(), any(), any(), any(), any())).thenReturn(false);

        var result = service.classifyItem(
                "Some Title", MediaCategory.AUDIO, "CD", "Label", 2001,
                900L, List.of(5L), List.of(5L), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.NOT_OWNED);
        verifyNoInteractions(collectionEntryRepository);
    }

    @Test
    void classifyItem_titleFallback_ownedWhenFormatMatches() {
        Item owned = Item.builder().id(42L).format("Vinyl LP").build();
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), anyString(), any())).thenReturn(List.of(owned));

        var result = service.classifyItem(
                "Abbey Road", MediaCategory.AUDIO, "Vinyl LP", null, null,
                null, null, List.of(), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.OWNED);
        assertThat(result.itemId()).isEqualTo(42L);
    }

    @Test
    void classifyItem_titleFallback_differentVersionWhenFormatDoesNotMatch() {
        Item owned = Item.builder().id(42L).format("CD").build();
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), anyString(), any())).thenReturn(List.of(owned));

        var result = service.classifyItem(
                "Abbey Road", MediaCategory.AUDIO, "Vinyl LP", null, null,
                null, null, List.of(), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.DIFFERENT_VERSION);
        assertThat(result.itemId()).isEqualTo(42L);
    }

    @Test
    void classifyItem_titleFallback_notOwnedChecksTasteProfile() {
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), anyString(), any())).thenReturn(List.of());
        TasteProfile profile = TasteProfile.builder().totalItems(50).build();
        when(tasteProfileService.getOrCompute(USER_ID)).thenReturn(profile);
        when(tasteProfileService.scoreInteresting(eq(profile), any(), any(), any(), any())).thenReturn(false);

        var result = service.classifyItem(
                "Some New Thing", MediaCategory.PRINT, "Book", "Publisher", 2020,
                null, null, List.of(), USER_ID);

        assertThat(result.ownedStatus()).isEqualTo(OwnedStatus.NOT_OWNED);
        assertThat(result.itemId()).isNull();
    }

    @Test
    void normalizeTitle_stripsPunctuationAndCollapsesWhitespace() {
        assertThat(service.normalizeTitle("Guns N' Roses -  Appetite for Destruction!"))
                .isEqualTo(service.normalizeTitle("guns n roses appetite for destruction"));
    }
}
