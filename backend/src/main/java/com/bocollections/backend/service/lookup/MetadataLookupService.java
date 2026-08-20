package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.ResolvedBarcode;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.ResolvedBarcodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataLookupService {

    // Every lookup service swallows network errors/timeouts/malformed responses into the same
    // "nothing found" Optional.empty() as a genuine no-match — there's no way from here to tell
    // a transient failure apart from a real miss. A permanent negative cache would let a single
    // bad UPCitemdb/TMDB round-trip poison a barcode forever, so negative entries expire and get
    // retried; a positive entry never expires, since a barcode's actual product identity doesn't change.
    private static final long NEGATIVE_CACHE_TTL_HOURS = 24;

    private final ItemRepository itemRepository;
    private final CollectionEntryRepository entryRepository;
    private final ResolvedBarcodeRepository resolvedBarcodeRepository;
    private final OpenLibraryService openLibraryService;
    private final MusicBrainzService musicBrainzService;
    private final DiscogsService discogsService;
    private final UpcItemDbService upcItemDbService;
    private final TmdbService tmdbService;
    private final IgdbService igdbService;
    private final TheGamesDbService theGamesDbService;
    private final EbayService ebayService;
    private final ObjectMapper objectMapper;

    public LookupResult lookup(String barcode, Long userId) {
        return lookup(barcode, userId, Set.of(), Set.of());
    }

    public LookupResult lookup(String barcode, Long userId, Set<String> excludeSources) {
        return lookup(barcode, userId, excludeSources, Set.of());
    }

    /**
     * @param excludeSources sources to skip entirely in the waterfall (e.g. after the user
     *                        rejects a specific source's match as wrong) — {@code "DISCOGS"},
     *                        {@code "MUSICBRAINZ"}, {@code "TMDB"}, {@code "OPEN_LIBRARY"}.
     * @param excludeExternalIds TMDB ids to skip while still retrying TMDB itself — a wrong
     *                            barcode match is usually TMDB's title search picking the wrong
     *                            movie (e.g. "Judge Dredd" 1995 outranking "Dredd" 2012 on
     *                            popularity), not TMDB being the wrong source entirely, so this
     *                            lets the next-best candidate from the same search be tried
     *                            instead of giving up on TMDB altogether. Both exclusion sets
     *                            bypass the positive cache read: it's keyed only by barcode, not
     *                            barcode+source+id, so a cached result that was just rejected
     *                            has to be re-resolved rather than returned as-is.
     */
    public LookupResult lookup(String barcode, Long userId, Set<String> excludeSources, Set<String> excludeExternalIds) {
        // 1. Check own catalogue first
        Optional<Item> existing = itemRepository.findByBarcode(barcode);
        if (existing.isPresent()) {
            Item item = existing.get();
            List<Long> ownedIn = entryRepository.findByUserIdAndItemId(userId, item.getId())
                    .stream().map(CollectionEntry::getCollectionId).toList();

            return LookupResult.builder()
                    .barcode(barcode)
                    .source("CATALOGUE")
                    .category(item.getCategory())
                    .format(item.getFormat())
                    .title(item.getTitle())
                    .subtitle(item.getSubtitle())
                    .description(item.getDescription())
                    .coverUrl(item.getCoverUrl())
                    .releaseYear(item.getReleaseYear())
                    .publisher(item.getPublisher())
                    .externalId(item.getExternalId())
                    .metadata(item.getMetadata())
                    .existingItemId(item.getId())
                    .ownedInCollections(ownedIn)
                    .build();
        }

        // 2. Check the shared barcode cache — a barcode's identity doesn't depend on who's
        // asking, so any prior resolution short-circuits every external call. A negative entry
        // only short-circuits while still fresh (see NEGATIVE_CACHE_TTL_HOURS above); once stale
        // it's treated as a miss and re-attempted, updating this same row rather than inserting.
        Optional<ResolvedBarcode> cached = resolvedBarcodeRepository.findByBarcode(barcode);
        ResolvedBarcode cacheRowToOverwrite = null;
        boolean cachedResultRejected = cached.isPresent() && cached.get().isFound() && (
                excludeSources.contains(cached.get().getSource())
                        || ("TMDB".equals(cached.get().getSource()) && cached.get().getExternalId() != null
                                && excludeExternalIds.contains(cached.get().getExternalId())));
        if (cachedResultRejected) {
            // The cached result is the exact match the caller just rejected — it can't be
            // returned as-is, but the row itself gets reused/overwritten below once the
            // waterfall (skipping that source/candidate) finds a replacement.
            cacheRowToOverwrite = cached.get();
        } else if (cached.isPresent()) {
            ResolvedBarcode r = cached.get();
            if (r.isFound()) {
                return LookupResult.builder()
                        .barcode(barcode)
                        .source(r.getSource())
                        .category(r.getCategory())
                        .format(r.getFormat())
                        .title(r.getTitle())
                        .subtitle(r.getSubtitle())
                        .description(r.getDescription())
                        .coverUrl(r.getCoverUrl())
                        .releaseYear(r.getReleaseYear())
                        .publisher(r.getPublisher())
                        .externalId(r.getExternalId())
                        .metadata(r.getMetadata())
                        .ownedInCollections(List.of())
                        .build();
            }
            boolean stale = r.getCreatedAt().isBefore(LocalDateTime.now().minusHours(NEGATIVE_CACHE_TTL_HOURS));
            if (!stale) {
                return LookupResult.builder()
                        .barcode(barcode)
                        .source("NOT_FOUND")
                        .ownedInCollections(List.of())
                        .build();
            }
            cacheRowToOverwrite = r; // fall through and re-attempt, updating this row instead of inserting
        }

        // 3. Route to the right external service based on barcode structure
        Optional<LookupResult> result;

        if (isIsbn(barcode)) {
            result = excludeSources.contains("OPEN_LIBRARY") ? Optional.empty() : openLibraryService.lookupByIsbn(barcode);
        } else {
            // Try Discogs first (best barcode coverage for audio), then MusicBrainz, then
            // resolve a title via UPCitemdb and search TMDB (VIDEO) then IGDB (GAME) with it —
            // neither has a barcode-native source of its own — first success in the chain wins.
            // Any source the caller already rejected (see excludeSources) is skipped so the
            // waterfall continues to the next one instead of handing back the same rejected match.
            boolean discogsExcluded = excludeSources.contains("DISCOGS");
            result = discogsExcluded ? Optional.empty() : discogsService.lookupByBarcode(barcode);
            if (result.isEmpty() && !excludeSources.contains("MUSICBRAINZ")) {
                log.debug("Barcode {}: DISCOGS {}, trying MUSICBRAINZ", barcode,
                        discogsExcluded ? "excluded (rejected)" : discogsService.isConfigured() ? "miss" : "miss (DISCOGS_TOKEN not configured)");
                result = musicBrainzService.lookupByBarcode(barcode);
            }
            if (result.isEmpty() && (!excludeSources.contains("TMDB") || !excludeSources.contains("IGDB"))) {
                log.debug("Barcode {}: MUSICBRAINZ miss, trying UPCITEMDB+TMDB/IGDB", barcode);
                Optional<UpcItemDbService.UpcItemDbResult> upcResult = upcItemDbService.lookup(barcode);

                if (upcResult.isPresent() && !excludeSources.contains("TMDB")) {
                    UpcItemDbService.UpcItemDbResult u = upcResult.get();
                    if (!tmdbService.isConfigured()) {
                        // The specific gap that made this chain silently fail end-to-end despite
                        // UPCitemdb doing its job correctly — worth its own line, not just a miss.
                        log.info("Barcode {}: UPCITEMDB resolved title \"{}\" but TMDB_API_KEY is not configured — cannot go further", barcode, u.title());
                    }
                    result = tmdbService.searchByTitle(u.title(), excludeExternalIds)
                            .map(r -> {
                                // UPCitemdb is the only source in this chain that actually knows which
                                // physical disc this barcode is for — TMDB only knows the film itself.
                                if (u.format() != null) r.setFormat(u.format());
                                List<String> images = mergedImages(u.images(), ebayService.lookupImages(barcode));
                                return promoteRealPhotoAsCover(withPhysicalPhotos(withDistributor(r, u.brand()), images), images);
                            });
                }

                if (result.isEmpty() && upcResult.isPresent() && !excludeSources.contains("IGDB")) {
                    UpcItemDbService.UpcItemDbResult u = upcResult.get();
                    result = igdbService.searchByTitle(u.title())
                            .map(r -> {
                                List<String> images = mergedImages(
                                        u.images(), theGamesDbService.searchBoxArt(u.title()), ebayService.lookupImages(barcode));
                                return promoteRealPhotoAsCover(withPhysicalPhotos(withDistributor(r, u.brand()), images), images);
                            });
                }
            }
        }

        LookupResult finalResult = result.map(r -> {
            r.setBarcode(barcode);
            r.setOwnedInCollections(List.of());
            return r;
        }).orElse(LookupResult.builder()
                .barcode(barcode)
                .source("NOT_FOUND")
                .ownedInCollections(List.of())
                .build());

        log.info("Barcode {}: resolved via {}", barcode, finalResult.getSource());
        // A NOT_FOUND from an exclusion-constrained retry only means "nothing left once you skip
        // what was already rejected" — it says nothing about whether the barcode has a real match
        // under a normal, unconstrained lookup. Caching it as a blanket negative would poison the
        // barcode for every future caller (including ones with no exclusions at all) for the full
        // negative-cache TTL, which is strictly worse than just leaving the previous cache entry
        // (if any) alone. Only a positive result from an exclusion retry is safe to write through.
        boolean exclusionRetry = !excludeSources.isEmpty() || !excludeExternalIds.isEmpty();
        if (!exclusionRetry || !"NOT_FOUND".equals(finalResult.getSource())) {
            cacheResult(barcode, finalResult, cacheRowToOverwrite);
        }
        return finalResult;
    }

    /** Write-through cache: never throws — a duplicate-barcode race is rare and not worth failing the request over. */
    private void cacheResult(String barcode, LookupResult result, ResolvedBarcode existing) {
        try {
            boolean found = !"NOT_FOUND".equals(result.getSource());
            ResolvedBarcode row = existing != null ? existing : ResolvedBarcode.builder().barcode(barcode).build();
            if (existing != null) row.setCreatedAt(LocalDateTime.now()); // reset the TTL window on refresh (no @PreUpdate hook for this)
            row.setFound(found);
            row.setCategory(found ? result.getCategory() : null);
            row.setFormat(found ? result.getFormat() : null);
            row.setTitle(found ? result.getTitle() : null);
            row.setSubtitle(found ? result.getSubtitle() : null);
            row.setDescription(found ? result.getDescription() : null);
            row.setCoverUrl(found ? result.getCoverUrl() : null);
            row.setReleaseYear(found ? result.getReleaseYear() : null);
            row.setPublisher(found ? result.getPublisher() : null);
            row.setExternalId(found ? result.getExternalId() : null);
            row.setSource(found ? result.getSource() : null);
            row.setMetadata(found ? result.getMetadata() : null);
            resolvedBarcodeRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            log.debug("Barcode {} was cached concurrently, skipping duplicate write", barcode);
        }
    }

    /**
     * Best-effort fallback used only when a physical item couldn't be matched by barcode at
     * all (see bulk-scan-mode spec) — finds a representative cover/edition by title so an
     * otherwise-unmatched draft isn't left with zero imagery. Routes by category since none of
     * these sources does general cross-category title search; OTHER has no wired source and
     * simply gets nothing back.
     */
    public Optional<LookupResult> lookupByTitle(String title, MediaCategory category) {
        if (title == null || title.isBlank() || category == null) return Optional.empty();
        return switch (category) {
            case PRINT -> openLibraryService.searchByTitle(title);
            case AUDIO -> discogsService.searchByTitle(title);
            case VIDEO -> tmdbService.searchByTitle(title);
            case GAME -> igdbService.searchByTitle(title);
            default -> Optional.empty();
        };
    }

    /** Combines image lists from multiple sources in priority order, deduping repeats. */
    @SafeVarargs
    private List<String> mergedImages(List<String>... sources) {
        Set<String> combined = new LinkedHashSet<>();
        for (List<String> source : sources) {
            if (source != null) combined.addAll(source);
        }
        return List.copyOf(combined);
    }

    private boolean isIsbn(String barcode) {
        // ISBN-13 starts with 978 or 979; ISBN-10 is 10 digits
        return (barcode.length() == 13 && (barcode.startsWith("978") || barcode.startsWith("979")))
                || barcode.length() == 10;
    }

    /**
     * UPCitemdb's `brand` field identifies the specific distributor of *this* physical release
     * (e.g. "Mill Creek", "Criterion Collection") — a different concept from TMDB's
     * studio/production-company data, and one collectors care about (mass-market discount prints
     * vs. collector's-edition labels aren't interchangeable even for the same film). Folded into
     * the TMDB result's existing metadata JSON as `distributor` rather than a dedicated field,
     * matching how every other "extra detail" already flows through that same blob.
     */
    private LookupResult withDistributor(LookupResult result, String distributor) {
        if (distributor == null) return result;
        try {
            ObjectNode extra = result.getMetadata() != null
                    ? (ObjectNode) objectMapper.readTree(result.getMetadata())
                    : objectMapper.createObjectNode();
            extra.put("distributor", distributor);
            result.setMetadata(objectMapper.writeValueAsString(extra));
        } catch (Exception e) {
            log.debug("Could not attach distributor to TMDB metadata: {}", e.getMessage());
        }
        return result;
    }

    /**
     * UPCitemdb's `images` are real photos of *this specific physical product* (front cover, and
     * often back cover/disc shots) scraped from retailer listings — unlike TMDB's `posterOptions`,
     * which are always official promotional front-cover art for the film only, never packaging.
     * Folded into the same `metadata.physicalPhotos` array ScanSessionService downloads alongside
     * `posterOptions` so the review screen gets real product-photo variety, not just more posters.
     * Capped small: retailer listings are noisy and often repeat the same photo across sellers, so
     * only the first few are kept — content-hash dedup on download (see ScanSessionService) drops
     * exact repeats, but capping here avoids fetching a pile of near-identical listing photos.
     */
    private LookupResult withPhysicalPhotos(LookupResult result, List<String> images) {
        if (images == null || images.isEmpty()) return result;
        try {
            ObjectNode extra = result.getMetadata() != null
                    ? (ObjectNode) objectMapper.readTree(result.getMetadata())
                    : objectMapper.createObjectNode();
            extra.putPOJO("physicalPhotos", images.stream().limit(4).toList());
            result.setMetadata(objectMapper.writeValueAsString(extra));
        } catch (Exception e) {
            log.debug("Could not attach physical photos to TMDB metadata: {}", e.getMessage());
        }
        return result;
    }

    /**
     * A real photo of the physical release (front cover, often showing the case/sleeve — as
     * opposed to TMDB's promotional poster art for the film) makes a much better primary
     * {@code coverUrl} for a physical-media collection. The TMDB poster that would otherwise have
     * won isn't discarded — it's folded into {@code posterOptions} so it's still offered as an
     * alternate on the review screen, just no longer the default.
     */
    private LookupResult promoteRealPhotoAsCover(LookupResult result, List<String> images) {
        if (images == null || images.isEmpty()) return result;
        String posterCoverUrl = result.getCoverUrl();
        result.setCoverUrl(images.get(0));
        if (posterCoverUrl == null) return result;
        try {
            ObjectNode extra = result.getMetadata() != null
                    ? (ObjectNode) objectMapper.readTree(result.getMetadata())
                    : objectMapper.createObjectNode();
            List<String> posterOptions = new java.util.ArrayList<>();
            extra.path("posterOptions").forEach(n -> posterOptions.add(n.asText(null)));
            if (!posterOptions.contains(posterCoverUrl)) posterOptions.add(0, posterCoverUrl);
            extra.putPOJO("posterOptions", posterOptions);
            result.setMetadata(objectMapper.writeValueAsString(extra));
        } catch (Exception e) {
            log.debug("Could not fold TMDB poster into posterOptions: {}", e.getMessage());
        }
        return result;
    }
}
