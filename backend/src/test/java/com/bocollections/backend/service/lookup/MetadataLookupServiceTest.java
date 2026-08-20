package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.ResolvedBarcode;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.ResolvedBarcodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetadataLookupService — first test coverage this class has had. Focused on the
 * new shared barcode cache and the extended non-ISBN fallback chain (Discogs -> MusicBrainz ->
 * UPCitemdb -> TMDB), since the catalogue-check and ISBN branches are unchanged behavior.
 */
@ExtendWith(MockitoExtension.class)
class MetadataLookupServiceTest {

    @Mock private ItemRepository itemRepository;
    @Mock private CollectionEntryRepository entryRepository;
    @Mock private ResolvedBarcodeRepository resolvedBarcodeRepository;
    @Mock private OpenLibraryService openLibraryService;
    @Mock private MusicBrainzService musicBrainzService;
    @Mock private DiscogsService discogsService;
    @Mock private UpcItemDbService upcItemDbService;
    @Mock private TmdbService tmdbService;
    @Mock private IgdbService igdbService;
    @Mock private TheGamesDbService theGamesDbService;
    @Mock private EbayService ebayService;

    private MetadataLookupService service;

    private static final Long USER_ID = 1L;
    private static final String DVD_UPC = "024543008194";

    @BeforeEach
    void setUp() {
        service = new MetadataLookupService(itemRepository, entryRepository, resolvedBarcodeRepository,
                openLibraryService, musicBrainzService, discogsService, upcItemDbService, tmdbService,
                igdbService, theGamesDbService, ebayService, new ObjectMapper());
        lenient().when(itemRepository.findByBarcode(any())).thenReturn(Optional.empty());
    }

    @Test
    void lookup_cacheHitFound_returnsCachedResultWithNoExternalCalls() {
        ResolvedBarcode cached = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(true).category(MediaCategory.VIDEO).format("DVD")
                .title("Big Momma's House").source("TMDB").build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(cached));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("TMDB");
        assertThat(result.getTitle()).isEqualTo("Big Momma's House");
        verifyNoInteractions(discogsService, musicBrainzService, upcItemDbService, tmdbService);
    }

    @Test
    void lookup_cacheHitNotFound_returnsNotFoundWithNoExternalCalls() {
        ResolvedBarcode cached = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(false).createdAt(LocalDateTime.now().minusHours(1)).build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(cached));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(discogsService, musicBrainzService, upcItemDbService, tmdbService);
    }

    @Test
    void lookup_isbnPath_unchangedAndCachesAfterward() {
        String isbn = "9780141439518";
        when(resolvedBarcodeRepository.findByBarcode(isbn)).thenReturn(Optional.empty());
        LookupResult openLibraryResult = LookupResult.builder().source("OPEN_LIBRARY").title("Pride and Prejudice").build();
        when(openLibraryService.lookupByIsbn(isbn)).thenReturn(Optional.of(openLibraryResult));

        LookupResult result = service.lookup(isbn, USER_ID);

        assertThat(result.getSource()).isEqualTo("OPEN_LIBRARY");
        verifyNoInteractions(discogsService, musicBrainzService, upcItemDbService, tmdbService);
        verify(resolvedBarcodeRepository).save(argThat(r -> r.isFound() && "OPEN_LIBRARY".equals(r.getSource())));
    }

    @Test
    void lookup_nonIsbnPath_discogsSucceeds_shortCircuitsRemainingChain() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        LookupResult discogsResult = LookupResult.builder().source("DISCOGS").title("Some Album").build();
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.of(discogsResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("DISCOGS");
        verifyNoInteractions(musicBrainzService, upcItemDbService, tmdbService);
    }

    @Test
    void lookup_nonIsbnPath_fallsThroughToUpcItemDbAndTmdb_whenAudioSourcesEmpty() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Big Momma's House", null, null, List.of())));
        LookupResult tmdbResult = LookupResult.builder()
                .source("TMDB").category(MediaCategory.VIDEO).format("DVD").title("Big Momma's House").build();
        when(tmdbService.searchByTitle("Big Momma's House", Set.of())).thenReturn(Optional.of(tmdbResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("TMDB");
        assertThat(result.getTitle()).isEqualTo("Big Momma's House");
        verify(resolvedBarcodeRepository).save(argThat(r ->
                r.isFound() && r.getCategory() == MediaCategory.VIDEO && "TMDB".equals(r.getSource())));
    }

    @Test
    void lookup_upcItemDbBrand_isFoldedIntoTmdbResultMetadataAsDistributor() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Coyote Ugly", "Mill Creek", null, List.of())));
        LookupResult tmdbResult = LookupResult.builder()
                .source("TMDB").title("Coyote Ugly").metadata("{\"tagline\":\"hi\"}").build();
        when(tmdbService.searchByTitle("Coyote Ugly", Set.of())).thenReturn(Optional.of(tmdbResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getMetadata()).contains("\"distributor\":\"Mill Creek\"").contains("\"tagline\":\"hi\"");
    }

    // Regression test: TMDB never set a real format (it only knows the film, not the disc), and
    // used to be hardcoded to "DVD" — silently mislabeling every Blu-ray. UPCitemdb's title tag
    // is the only source in this chain that actually knows the physical format.
    @Test
    void lookup_upcItemDbFormatTag_overridesTmdbResultFormat() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Dredd", null, "Blu-ray", List.of())));
        LookupResult tmdbResult = LookupResult.builder().source("TMDB").title("Dredd").build();
        when(tmdbService.searchByTitle("Dredd", Set.of())).thenReturn(Optional.of(tmdbResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getFormat()).isEqualTo("Blu-ray");
    }

    @Test
    void lookup_everySourceExhausted_returnsNotFoundAndCachesNegative() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.empty());

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(tmdbService);
        verify(resolvedBarcodeRepository).save(argThat(r -> !r.isFound() && r.getTitle() == null));
    }

    @Test
    void lookup_freshNegativeCache_shortCircuitsWithoutRetrying() {
        ResolvedBarcode fresh = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(false).createdAt(LocalDateTime.now().minusHours(1)).build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(fresh));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(discogsService, musicBrainzService, upcItemDbService, tmdbService);
    }

    @Test
    void lookup_staleNegativeCache_retriesChainAndUpdatesSameRow() {
        ResolvedBarcode stale = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(false).createdAt(LocalDateTime.now().minusHours(48)).build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(stale));
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Big Momma's House", null, null, List.of())));
        LookupResult tmdbResult = LookupResult.builder()
                .source("TMDB").category(MediaCategory.VIDEO).title("Big Momma's House").build();
        when(tmdbService.searchByTitle("Big Momma's House", Set.of())).thenReturn(Optional.of(tmdbResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("TMDB");
        // Same row instance reused (updated in place), not a second row inserted for the same barcode.
        verify(resolvedBarcodeRepository).save(same(stale));
        assertThat(stale.isFound()).isTrue();
        assertThat(stale.getTitle()).isEqualTo("Big Momma's House");
    }

    @Test
    void lookup_excludingRejectedSource_skipsItAndTriesNext() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Big Momma's House", null, null, List.of())));
        LookupResult tmdbResult = LookupResult.builder().source("TMDB").title("Big Momma's House").build();
        when(tmdbService.searchByTitle("Big Momma's House", Set.of())).thenReturn(Optional.of(tmdbResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID, Set.of("DISCOGS"));

        assertThat(result.getSource()).isEqualTo("TMDB");
        verifyNoInteractions(discogsService);
    }

    @Test
    void lookup_excludingSourceOfCachedPositiveResult_bypassesCacheAndRetries() {
        ResolvedBarcode cached = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(true).source("DISCOGS").title("Wrong Match").build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(cached));
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Big Momma's House", null, null, List.of())));
        LookupResult tmdbResult = LookupResult.builder().source("TMDB").title("Big Momma's House").build();
        when(tmdbService.searchByTitle("Big Momma's House", Set.of())).thenReturn(Optional.of(tmdbResult));

        LookupResult result = service.lookup(DVD_UPC, USER_ID, Set.of("DISCOGS"));

        assertThat(result.getSource()).isEqualTo("TMDB");
        verifyNoInteractions(discogsService);
        // Same cache row overwritten in place with the new source, not left pointing at the rejected one.
        verify(resolvedBarcodeRepository).save(same(cached));
        assertThat(cached.getSource()).isEqualTo("TMDB");
        assertThat(cached.getTitle()).isEqualTo("Big Momma's House");
    }

    @Test
    void lookup_excludingAllSources_returnsNotFoundWithoutCallingAny() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());

        LookupResult result = service.lookup(DVD_UPC, USER_ID, Set.of("DISCOGS", "MUSICBRAINZ", "TMDB", "IGDB"));

        assertThat(result.getSource()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(discogsService, musicBrainzService, upcItemDbService, tmdbService, igdbService);
        // A NOT_FOUND that only happened because every source was excluded says nothing about
        // whether an unconstrained lookup would find something — must not be cached as a
        // blanket negative, or it'd poison the barcode for every future caller too.
        verify(resolvedBarcodeRepository, never()).save(any());
    }

    // Regression test for a real incident: testing the exclude-source retry against a barcode
    // that already had a correct cached TMDB match overwrote that cache row with NOT_FOUND,
    // silently breaking every future (non-rejecting) lookup of that barcode for a full 24h TTL.
    @Test
    void lookup_exclusionRetryExhaustsCandidates_leavesExistingCacheEntryUntouched() {
        ResolvedBarcode cached = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(true).source("TMDB").externalId("6282").title("Coyote Ugly").build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(cached));
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Coyote Ugly", null, null, List.of())));
        when(tmdbService.searchByTitle("Coyote Ugly", Set.of("6282"))).thenReturn(Optional.empty());

        LookupResult result = service.lookup(DVD_UPC, USER_ID, Set.of(), Set.of("6282"));

        assertThat(result.getSource()).isEqualTo("NOT_FOUND");
        verify(resolvedBarcodeRepository, never()).save(any());
        assertThat(cached.isFound()).isTrue();
        assertThat(cached.getSource()).isEqualTo("TMDB");
        assertThat(cached.getTitle()).isEqualTo("Coyote Ugly");
    }

    @Test
    void lookup_excludingRejectedTmdbId_retriesTmdbInsteadOfSkippingIt() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Dredd", null, null, List.of())));
        LookupResult secondCandidate = LookupResult.builder().source("TMDB").title("Judge Dredd").externalId("1").build();
        when(tmdbService.searchByTitle("Dredd", Set.of("2"))).thenReturn(Optional.of(secondCandidate));

        LookupResult result = service.lookup(DVD_UPC, USER_ID, Set.of(), Set.of("2"));

        assertThat(result.getSource()).isEqualTo("TMDB");
        assertThat(result.getTitle()).isEqualTo("Judge Dredd");
    }

    @Test
    void lookup_excludingCachedTmdbIdThatWasRejected_bypassesCacheAndRetries() {
        ResolvedBarcode cached = ResolvedBarcode.builder()
                .barcode(DVD_UPC).found(true).source("TMDB").externalId("2").title("Wrong Match").build();
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.of(cached));
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Dredd", null, null, List.of())));
        LookupResult nextCandidate = LookupResult.builder().source("TMDB").title("Judge Dredd").externalId("1").build();
        when(tmdbService.searchByTitle("Dredd", Set.of("2"))).thenReturn(Optional.of(nextCandidate));

        LookupResult result = service.lookup(DVD_UPC, USER_ID, Set.of(), Set.of("2"));

        assertThat(result.getSource()).isEqualTo("TMDB");
        assertThat(result.getTitle()).isEqualTo("Judge Dredd");
        verify(resolvedBarcodeRepository).save(same(cached));
        assertThat(cached.getExternalId()).isEqualTo("1");
    }

    @Test
    void lookupByTitle_videoCategory_delegatesToTmdb() {
        when(tmdbService.searchByTitle("Some Movie")).thenReturn(Optional.of(LookupResult.builder().source("TMDB").build()));

        Optional<LookupResult> result = service.lookupByTitle("Some Movie", MediaCategory.VIDEO);

        assertThat(result).isPresent();
        verify(tmdbService).searchByTitle("Some Movie");
    }

    @Test
    void lookupByTitle_gameCategory_delegatesToIgdb() {
        when(igdbService.searchByTitle("Some Game")).thenReturn(Optional.of(LookupResult.builder().source("IGDB").build()));

        Optional<LookupResult> result = service.lookupByTitle("Some Game", MediaCategory.GAME);

        assertThat(result).isPresent();
        verify(igdbService).searchByTitle("Some Game");
    }

    @Test
    void lookup_nonIsbnPath_fallsThroughToIgdb_whenTmdbMisses() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Some Game", null, null, List.of())));
        when(tmdbService.searchByTitle("Some Game", Set.of())).thenReturn(Optional.empty());
        LookupResult igdbResult = LookupResult.builder().source("IGDB").category(MediaCategory.GAME).title("Some Game").build();
        when(igdbService.searchByTitle("Some Game")).thenReturn(Optional.of(igdbResult));
        when(theGamesDbService.searchBoxArt("Some Game")).thenReturn(List.of("https://cdn.thegamesdb.net/front.jpg", "https://cdn.thegamesdb.net/back.jpg"));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("IGDB");
        assertThat(result.getTitle()).isEqualTo("Some Game");
        // The real front-cover photo (TheGamesDB's boxart, in this case) is promoted to coverUrl.
        assertThat(result.getCoverUrl()).isEqualTo("https://cdn.thegamesdb.net/front.jpg");
    }

    @Test
    void lookup_nonIsbnPath_ebayImagesAreMergedIntoTmdbResultPhysicalPhotos() {
        when(resolvedBarcodeRepository.findByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(discogsService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(musicBrainzService.lookupByBarcode(DVD_UPC)).thenReturn(Optional.empty());
        when(upcItemDbService.lookup(DVD_UPC)).thenReturn(Optional.of(new UpcItemDbService.UpcItemDbResult("Big Momma's House", null, null, List.of())));
        LookupResult tmdbResult = LookupResult.builder().source("TMDB").title("Big Momma's House").coverUrl("https://tmdb/poster.jpg").build();
        when(tmdbService.searchByTitle("Big Momma's House", Set.of())).thenReturn(Optional.of(tmdbResult));
        when(ebayService.lookupImages(DVD_UPC)).thenReturn(List.of("https://ebay/listing-photo.jpg"));

        LookupResult result = service.lookup(DVD_UPC, USER_ID);

        assertThat(result.getSource()).isEqualTo("TMDB");
        assertThat(result.getCoverUrl()).isEqualTo("https://ebay/listing-photo.jpg");
        assertThat(result.getMetadata()).contains("https://ebay/listing-photo.jpg");
        assertThat(result.getMetadata()).contains("https://tmdb/poster.jpg"); // demoted poster kept as an alternate
    }
}
