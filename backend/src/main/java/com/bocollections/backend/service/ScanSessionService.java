package com.bocollections.backend.service;

import com.bocollections.backend.dto.*;
import com.bocollections.backend.entity.Collection;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MatchKind;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.ItemPhoto;
import com.bocollections.backend.entity.ScanDraft;
import com.bocollections.backend.entity.ScanDraftPhoto;
import com.bocollections.backend.entity.ScanDraftStatus;
import com.bocollections.backend.entity.ScanSession;
import com.bocollections.backend.exception.ConflictException;
import com.bocollections.backend.exception.ForbiddenException;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.CollectionRepository;
import com.bocollections.backend.repository.ItemPhotoRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.ScanDraftCountProjection;
import com.bocollections.backend.repository.ScanDraftPhotoRepository;
import com.bocollections.backend.repository.ScanDraftRepository;
import com.bocollections.backend.repository.ScanSessionRepository;
import com.bocollections.backend.service.lookup.MetadataLookupService;
import com.bocollections.backend.service.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanSessionService {

    private static final HttpClient IMAGE_DOWNLOAD_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ScanSessionRepository sessionRepository;
    private final ScanDraftRepository draftRepository;
    private final ScanDraftPhotoRepository photoRepository;
    private final ItemPhotoRepository itemPhotoRepository;
    private final CollectionRepository collectionRepository;
    private final ItemRepository itemRepository;
    private final ItemService itemService;
    private final CollectionService collectionService;
    private final StorageService storageService;
    private final MetadataLookupService metadataLookupService;
    private final PlatformTransactionManager transactionManager;
    private final VisualScanService visualScanService;
    private final ObjectMapper objectMapper;

    // --- Sessions ---

    @Transactional
    public ScanSessionResponse createSession(ScanSessionRequest req, Long userId) {
        Collection collection = collectionRepository.findByIdAndUserId(req.getCollectionId(), userId)
                .orElseThrow(() -> new ForbiddenException("Collection not found"));

        ScanSession session = ScanSession.builder()
                .userId(userId)
                .collectionId(collection.getId())
                .build();
        session = sessionRepository.save(session);
        return toSessionResponse(session, collection, 0);
    }

    @Transactional(readOnly = true)
    public List<ScanSessionResponse> listSessions(Long userId) {
        List<ScanSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (sessions.isEmpty()) return List.of();

        Set<Long> sessionIds = sessions.stream().map(ScanSession::getId).collect(Collectors.toSet());
        Map<Long, Long> countMap = draftRepository.countsBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(ScanDraftCountProjection::getSessionId, ScanDraftCountProjection::getCount));

        Set<Long> collectionIds = sessions.stream().map(ScanSession::getCollectionId).collect(Collectors.toSet());
        Map<Long, Collection> collectionMap = collectionRepository.findAllById(collectionIds).stream()
                .collect(Collectors.toMap(Collection::getId, Function.identity()));

        return sessions.stream()
                .map(s -> toSessionResponse(s, collectionMap.get(s.getCollectionId()), countMap.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScanSessionResponse getSession(Long sessionId, Long userId) {
        ScanSession session = findSessionOrThrow(sessionId, userId);
        Collection collection = collectionRepository.findById(session.getCollectionId()).orElse(null);
        long count = draftRepository.countsBySessionIds(Set.of(sessionId)).stream()
                .findFirst().map(ScanDraftCountProjection::getCount).orElse(0L);
        return toSessionResponse(session, collection, count);
    }

    @Transactional
    public ScanSessionResponse updateSessionStatus(Long sessionId, ScanSessionStatusRequest req, Long userId) {
        ScanSession session = findSessionOrThrow(sessionId, userId);
        session.setStatus(req.getStatus());
        sessionRepository.save(session);
        return getSession(sessionId, userId);
    }

    @Transactional
    public void discardSession(Long sessionId, Long userId) {
        ScanSession session = findSessionOrThrow(sessionId, userId);
        List<ScanDraft> drafts = draftRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        // Approved drafts' photos were copied onto their item's own gallery (see
        // copyDraftPhotosToItem) and their coverUrl may still reference this storage key —
        // deleting them here would silently break already-approved items' images.
        List<Long> unreviewedDraftIds = drafts.stream()
                .filter(d -> d.getStatus() != ScanDraftStatus.APPROVED)
                .map(ScanDraft::getId)
                .toList();
        List<ScanDraftPhoto> photos = photoRepository.findByDraftIdInOrderBySortOrderAscIdAsc(unreviewedDraftIds);
        photos.forEach(p -> storageService.delete(p.getStorageKey()));
        sessionRepository.delete(session); // cascades drafts + photos rows
    }

    // --- Drafts ---

    @Transactional(readOnly = true)
    public List<ScanDraftResponse> listDrafts(Long sessionId, Long userId) {
        findSessionOrThrow(sessionId, userId);
        List<ScanDraft> drafts = draftRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (drafts.isEmpty()) return List.of();

        Map<Long, List<ScanDraftPhoto>> photosByDraft = photoRepository
                .findByDraftIdInOrderBySortOrderAscIdAsc(drafts.stream().map(ScanDraft::getId).toList()).stream()
                .collect(Collectors.groupingBy(ScanDraftPhoto::getDraftId));

        return drafts.stream().map(d -> toDraftResponse(d, photosByDraft.getOrDefault(d.getId(), List.of()), userId)).toList();
    }

    /**
     * Not itself @Transactional — the best-effort title-search fallback below is a blocking
     * external HTTP call, and it must complete before any DB transaction opens, not inside one
     * (a slow/unreachable source would otherwise hold a pooled connection for the whole
     * round-trip). Only the actual persistence, in the TransactionTemplate block, is transactional.
     */
    public ScanDraftResponse createDraft(Long sessionId, ScanDraftRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);

        ScanDraft draft = ScanDraft.builder()
                .sessionId(sessionId)
                .matchKind(req.getMatchKind())
                .existingItemId(req.getExistingItemId())
                .barcode(req.getBarcode())
                .category(req.getCategory())
                .format(req.getFormat())
                .title(req.getTitle())
                .subtitle(req.getSubtitle())
                .description(req.getDescription())
                .coverUrl(req.getCoverUrl())
                .releaseYear(req.getReleaseYear())
                .publisher(req.getPublisher())
                .metadata(req.getMetadata())
                .confidence(req.getConfidence())
                .externalSource(req.getExternalSource())
                .build();

        if (req.getMatchKind() == MatchKind.ALREADY_OWNED) {
            draft.setStatus(ScanDraftStatus.SKIPPED);
        }

        // Same-session duplicate flag — never blocks, just surfaces (collector may own two copies).
        if (draft.getBarcode() != null) {
            draftRepository.findFirstBySessionIdAndBarcodeOrderByCreatedAtAsc(sessionId, draft.getBarcode())
                    .ifPresent(existing -> draft.setDuplicateOfDraftId(existing.getId()));
        }

        // No cover recovered — either there was no barcode at all, or there was one but the
        // barcode waterfall (UPCitemdb's free-tier rate limit is the usual culprit for VIDEO)
        // came up empty. Vision's own read of the title is still worth a direct TMDB/OpenLibrary/
        // Discogs title search in either case — previously this only ran when draft.getBarcode()
        // was null, so a scanned-but-unresolved barcode silently gave up on imagery entirely even
        // though the title was sitting right there on the draft.
        if ((draft.getCoverUrl() == null || draft.getCoverUrl().isBlank())
                && draft.getTitle() != null && !draft.getTitle().isBlank()) {
            metadataLookupService.lookupByTitle(draft.getTitle(), draft.getCategory()).ifPresent(found -> {
                draft.setCoverUrl(found.getCoverUrl());
                if (draft.getPublisher() == null) draft.setPublisher(found.getPublisher());
                if (draft.getReleaseYear() == null) draft.setReleaseYear(found.getReleaseYear());
                // Merges in TMDB's posterOptions / UPCitemdb's physicalPhotos so
                // downloadAlternateImages below still has candidates — a plain overwrite would
                // recover a cover but wipe out whatever vision already read off the box (discCount,
                // cast, etc., set on draft.metadata at creation time above).
                draft.setMetadata(mergeMetadata(draft.getMetadata(), found.getMetadata()));
            });
        }

        // A matched online cover (from the barcode/vision lookup) is worth keeping alongside the
        // user's own shots, not just as the `coverUrl` field — so it shows up in the same photo
        // gallery on review, and survives even if the item is later re-matched to a different
        // cover. Only fetched when the capture flow didn't already supply its own REFERENCE shot
        // (e.g. a merge that already carried one over). Downloaded outside the transaction below,
        // same reasoning as the title-search fallback above: a slow/unreachable CDN shouldn't hold
        // a pooled DB connection hostage.
        boolean hasReferencePhoto = req.getPhotos() != null
                && req.getPhotos().stream().anyMatch(p -> "REFERENCE".equals(p.getAngle()));
        DownloadedImage referenceImage = (!hasReferencePhoto && draft.getCoverUrl() != null && !draft.getCoverUrl().isBlank())
                ? downloadImage(draft.getCoverUrl()).orElse(null)
                : null;

        // TMDB (see TmdbService.fetchExtraMetadata) can supply alternate poster options beyond the
        // default one already fetched above, and UPCitemdb (see MetadataLookupService.withPhysicalPhotos)
        // can supply real photos of this specific physical product — front/back/disc, not just
        // promotional art. Pull both in so the review screen has real cover variety instead of
        // whatever TMDB happened to pick as default. Content-hash deduped against the primary
        // reference and each other, since retailer listings frequently repeat the exact same photo.
        // Same "outside the transaction" reasoning as above.
        List<DownloadedImage> alternateImages = hasReferencePhoto
                ? List.of()
                : downloadAlternateImages(draft.getMetadata(), draft.getCoverUrl(), referenceImage, 4);

        return new TransactionTemplate(transactionManager).execute(status -> {
            ScanDraft saved = draftRepository.save(draft);

            List<ScanDraftPhoto> photos = new ArrayList<>();
            // A fresh draft, so a plain running counter (not a max-lookup) is enough — captured
            // photos first, then reference/alternate images after, matching creation order.
            int[] order = { 0 };
            if (req.getPhotos() != null && !req.getPhotos().isEmpty()) {
                req.getPhotos().forEach(p -> {
                    byte[] bytes = Base64.getDecoder().decode(p.getImageBase64());
                    String key = storageService.store(bytes, safeMimeType(p.getImageMimeType()));
                    photos.add(photoRepository.save(ScanDraftPhoto.builder()
                            .draftId(saved.getId())
                            .storageKey(key)
                            .angle(p.getAngle())
                            .sortOrder(order[0]++)
                            .build()));
                });
            }
            if (referenceImage != null) {
                String key = storageService.store(referenceImage.bytes(), referenceImage.contentType());
                photos.add(photoRepository.save(ScanDraftPhoto.builder()
                        .draftId(saved.getId())
                        .storageKey(key)
                        .angle("REFERENCE")
                        .sortOrder(order[0]++)
                        .build()));
            }
            for (DownloadedImage image : alternateImages) {
                String key = storageService.store(image.bytes(), image.contentType());
                photos.add(photoRepository.save(ScanDraftPhoto.builder()
                        .draftId(saved.getId())
                        .storageKey(key)
                        .angle("REFERENCE")
                        .sortOrder(order[0]++)
                        .build()));
            }

            // A manually-captured FRONT shot is a real photo of the collector's actual copy —
            // prefer it as the default cover over whatever was grabbed online. The online image
            // stays in the gallery above either way, so the user can still pick it as cover manually.
            photos.stream()
                    .filter(p -> "FRONT".equals(p.getAngle()))
                    .findFirst()
                    .ifPresent(front -> saved.setCoverUrl(storageService.publicUrl(front.getStorageKey())));

            return toDraftResponse(saved, photos, userId);
        });
    }

    private record DownloadedImage(byte[] bytes, String contentType) {}

    /** Combines two metadata JSON blobs, keeping {@code base}'s values on key collision (vision's
     * own read of the box wins over a generic title-search result) while adding whatever keys
     * {@code addition} has that {@code base} doesn't (e.g. posterOptions/physicalPhotos). Either
     * side may be null/blank/malformed — best-effort, never throws. */
    private String mergeMetadata(String base, String addition) {
        if (addition == null || addition.isBlank()) return base;
        if (base == null || base.isBlank()) return addition;
        try {
            ObjectNode merged = ((ObjectNode) objectMapper.readTree(addition)).deepCopy();
            merged.setAll((ObjectNode) objectMapper.readTree(base));
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.debug("Could not merge metadata blobs: {}", e.getMessage());
            return base;
        }
    }

    /** Reads `metadata.posterOptions` (TMDB alternate poster art — see TmdbService) and
     * `metadata.physicalPhotos` (real photos of this specific product — see
     * MetadataLookupService.withPhysicalPhotos) and downloads up to `maxCount` of them combined,
     * skipping the URL already fetched as the primary reference photo. Deduped against the primary
     * reference and each other in two passes: a cheap exact-byte hash first (catches the identical-
     * file case), then a perceptual hash (see {@link #perceptualHash}) that catches the more common
     * case — the *same photo* re-served at a different size/compression/crop by a different
     * retailer listing or CDN, which is byte-different but visually identical. Genuinely different
     * shots (front vs. back vs. disc) score far apart on the perceptual hash and are kept.
     * Best-effort: a missing/malformed metadata blob or a failed download just means fewer
     * alternate photos, never a failed draft. */
    private List<DownloadedImage> downloadAlternateImages(String metadataJson, String primaryCoverUrl,
                                                            DownloadedImage referenceImage, int maxCount) {
        if (metadataJson == null || metadataJson.isBlank()) return List.of();
        try {
            JsonNode metadata = objectMapper.readTree(metadataJson);
            List<String> candidateUrls = new ArrayList<>();
            metadata.path("posterOptions").forEach(n -> candidateUrls.add(n.asText(null)));
            metadata.path("physicalPhotos").forEach(n -> candidateUrls.add(n.asText(null)));
            if (candidateUrls.isEmpty()) return List.of();

            Set<String> seenExactHashes = new HashSet<>();
            List<Long> seenPerceptualHashes = new ArrayList<>();
            if (referenceImage != null) {
                seenExactHashes.add(sha256(referenceImage.bytes()));
                Long refHash = perceptualHash(referenceImage.bytes());
                if (refHash != null) seenPerceptualHashes.add(refHash);
            }

            List<DownloadedImage> downloaded = new ArrayList<>();
            for (String url : candidateUrls) {
                if (downloaded.size() >= maxCount) break;
                if (url == null || url.equals(primaryCoverUrl)) continue;
                Optional<DownloadedImage> image = downloadImage(url);
                if (image.isEmpty()) continue;
                byte[] bytes = image.get().bytes();

                if (!seenExactHashes.add(sha256(bytes))) continue; // byte-identical — already have this

                Long hash = perceptualHash(bytes);
                if (hash != null) {
                    boolean nearDuplicate = seenPerceptualHashes.stream()
                            .anyMatch(seen -> Long.bitCount(seen ^ hash) <= PERCEPTUAL_HASH_THRESHOLD);
                    if (nearDuplicate) continue;
                    seenPerceptualHashes.add(hash);
                }
                downloaded.add(image.get());
            }
            return downloaded;
        } catch (Exception e) {
            log.debug("Could not parse alternate image candidates from draft metadata: {}", e.getMessage());
            return List.of();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available on the JVM
        }
    }

    /** Out of 64 compared bits — low enough that only near-identical images (the same photo
     * resized/recompressed/lightly cropped by a different retailer or CDN) match, not just
     * "similarly-composed cover art" (two different films' posters can easily share a dark
     * background and centered title text, which would collide at a looser threshold). */
    private static final int PERCEPTUAL_HASH_THRESHOLD = 6;

    /**
     * Difference hash (dHash): shrink to a 9x8 grayscale grid, then for each row set one bit per
     * adjacent-pixel comparison (left brighter than right, or not) — 64 bits total. Two images
     * with a low Hamming distance between their hashes look alike to the human eye, even when
     * their underlying bytes differ completely (different JPEG quality, a resize, a few pixels of
     * crop/watermark). Cheap and license-free, unlike the exact-byte hash above which only catches
     * literally-identical files. Returns null (never a hash of 0, which is itself a valid hash) on
     * anything ImageIO can't decode — callers must treat null as "no perceptual comparison possible",
     * not "duplicate of everything".
     */
    private static Long perceptualHash(byte[] imageBytes) {
        try {
            java.awt.image.BufferedImage original = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            if (original == null) return null;

            java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(9, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = resized.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, 9, 8, null);
            g.dispose();

            long hash = 0;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (luminance(resized.getRGB(x, y)) < luminance(resized.getRGB(x + 1, y))) {
                        hash |= (1L << bit);
                    }
                    bit++;
                }
            }
            return hash;
        } catch (Exception e) {
            return null;
        }
    }

    private static int luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** Best-effort: swallows every failure (unreachable host, 404, timeout) — a missing reference
     * photo is a shrug, not a reason to fail draft creation. A real browser User-Agent is required
     * here — several source CDNs (eBay, retailer listing images, etc.) 403 requests that don't look
     * like a browser, which silently produced zero reference photos with no visible error. */
    private Optional<DownloadedImage> downloadImage(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (compatible; BOCollections/1.0)")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = IMAGE_DOWNLOAD_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("Reference photo download from {} returned HTTP {}", url, response.statusCode());
                return Optional.empty();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg");
            return Optional.of(new DownloadedImage(response.body(), safeMimeType(contentType)));
        } catch (Exception e) {
            log.warn("Could not download reference photo from {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public ScanDraftResponse updateDraft(Long sessionId, Long draftId, ScanDraftUpdateRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);

        // Partial update — the review UI only ever sends the handful of fields it actually lets
        // the user edit; a full overwrite would null out everything else (cover art, barcode, etc.)
        if (req.getBarcode() != null) draft.setBarcode(req.getBarcode());
        if (req.getCategory() != null) draft.setCategory(req.getCategory());
        if (req.getFormat() != null) draft.setFormat(req.getFormat());
        if (req.getTitle() != null) draft.setTitle(req.getTitle());
        if (req.getSubtitle() != null) draft.setSubtitle(req.getSubtitle());
        if (req.getDescription() != null) draft.setDescription(req.getDescription());
        if (req.getCoverUrl() != null) draft.setCoverUrl(req.getCoverUrl());
        if (req.getReleaseYear() != null) draft.setReleaseYear(req.getReleaseYear());
        if (req.getPublisher() != null) draft.setPublisher(req.getPublisher());
        if (req.getMetadata() != null) draft.setMetadata(req.getMetadata());
        if (req.getExternalSource() != null) draft.setExternalSource(req.getExternalSource());
        draft = draftRepository.save(draft);

        return toDraftResponse(draft, photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId), userId);
    }

    @Transactional
    public CollectionEntryResponse approveDraft(Long sessionId, Long draftId, Long userId) {
        ScanSession session = findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);

        Long itemId = draft.getExistingItemId();
        if (itemId == null) {
            ItemRequest itemReq = new ItemRequest();
            itemReq.setBarcode(draft.getBarcode());
            itemReq.setCategory(draft.getCategory() != null ? draft.getCategory() : MediaCategory.OTHER);
            itemReq.setFormat(draft.getFormat() != null ? draft.getFormat() : "Other");
            itemReq.setTitle(draft.getTitle());
            itemReq.setSubtitle(draft.getSubtitle());
            itemReq.setDescription(draft.getDescription());
            itemReq.setCoverUrl(draft.getCoverUrl());
            itemReq.setReleaseYear(draft.getReleaseYear());
            itemReq.setPublisher(draft.getPublisher());
            itemReq.setExternalSource(draft.getExternalSource() != null ? draft.getExternalSource() : "MANUAL");
            itemReq.setMetadata(draft.getMetadata());
            itemId = itemService.create(itemReq).getId();
        }
        copyDraftPhotosToItem(draftId, itemId);

        // A draft can already be SKIPPED (auto-detected as already-owned) and still get approved
        // via "add anyway" — in that case the entry already exists, so recover it instead of
        // calling addEntry (which would 409 on the very duplicate it's designed to prevent).
        Long finalItemId = itemId;
        CollectionEntryResponse entry = collectionService.findEntryForItem(session.getCollectionId(), itemId, userId)
                .orElseGet(() -> {
                    CollectionEntryRequest entryReq = new CollectionEntryRequest();
                    entryReq.setItemId(finalItemId);
                    return collectionService.addEntry(session.getCollectionId(), entryReq, userId);
                });

        draft.setStatus(ScanDraftStatus.APPROVED);
        draft.setExistingItemId(itemId);
        draftRepository.save(draft);

        return entry;
    }

    /**
     * The draft's own captured photos (front/back/spine, etc. — see ScanDraft's angle taxonomy)
     * otherwise never survive approval; only draft.coverUrl gets copied onto the new item via
     * ItemRequest.coverUrl. Skips storage keys already present on the item so re-approving an
     * already-approved draft (existingItemId branch) doesn't duplicate the gallery.
     */
    private void copyDraftPhotosToItem(Long draftId, Long itemId) {
        List<ScanDraftPhoto> draftPhotos = photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId);
        if (draftPhotos.isEmpty()) {
            return;
        }
        List<ItemPhoto> existingPhotos = itemPhotoRepository.findByItemIdOrderBySortOrderAscIdAsc(itemId);
        Set<String> existingKeys = existingPhotos.stream().map(ItemPhoto::getStorageKey).collect(Collectors.toSet());
        int nextOrder = existingPhotos.stream().mapToInt(ItemPhoto::getSortOrder).max().orElse(-1) + 1;
        for (ScanDraftPhoto draftPhoto : draftPhotos) {
            if (existingKeys.contains(draftPhoto.getStorageKey())) {
                continue;
            }
            itemPhotoRepository.save(ItemPhoto.builder()
                    .itemId(itemId)
                    .storageKey(draftPhoto.getStorageKey())
                    .angle(draftPhoto.getAngle())
                    .sortOrder(nextOrder++)
                    .build());
        }
    }

    @Transactional
    public void discardDraft(Long sessionId, Long draftId, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);
        photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId).forEach(p -> storageService.delete(p.getStorageKey()));
        draftRepository.delete(draft);
    }

    /** Lets a reviewer drop a specific photo (e.g. a blurry shot, or a fetched reference image
     * they don't want) without discarding the whole draft. */
    @Transactional
    public ScanDraftResponse deletePhoto(Long sessionId, Long draftId, Long photoId, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);
        ScanDraftPhoto photo = photoRepository.findById(photoId)
                .filter(p -> p.getDraftId().equals(draftId))
                .orElseThrow(() -> new NotFoundException("Photo not found: " + photoId));

        storageService.delete(photo.getStorageKey());
        photoRepository.delete(photo);

        return toDraftResponse(draft, photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId), userId);
    }

    @Transactional
    public ScanDraftResponse updatePhotoAngle(Long sessionId, Long draftId, Long photoId, String angle, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);
        ScanDraftPhoto photo = photoRepository.findById(photoId)
                .filter(p -> p.getDraftId().equals(draftId))
                .orElseThrow(() -> new NotFoundException("Photo not found: " + photoId));

        photo.setAngle(angle);
        photoRepository.save(photo);

        return toDraftResponse(draft, photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId), userId);
    }

    @Transactional
    public ScanDraftResponse reorderPhotos(Long sessionId, Long draftId, List<Long> photoIds, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);
        List<ScanDraftPhoto> photos = photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId);
        Map<Long, ScanDraftPhoto> remaining = photos.stream().collect(java.util.stream.Collectors.toMap(ScanDraftPhoto::getId, java.util.function.Function.identity()));

        int order = 0;
        for (Long id : photoIds) {
            ScanDraftPhoto p = remaining.remove(id);
            if (p != null) {
                p.setSortOrder(order++);
                photoRepository.save(p);
            }
        }
        for (ScanDraftPhoto p : photos) {
            if (remaining.containsKey(p.getId())) {
                p.setSortOrder(order++);
                photoRepository.save(p);
            }
        }
        return toDraftResponse(draft, photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId), userId);
    }

    /** Appends more photos to a draft still under review — e.g. the barcode/vision match looked
     * confident enough on the first shot, but the reviewer wants a disc/spine close-up before
     * approving. Same storage path createDraft already uses for its own photos. */
    @Transactional
    public ScanDraftResponse addPhotos(Long sessionId, Long draftId, List<ScanDraftPhotoRequest> photos, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ScanDraft draft = findDraftOrThrow(sessionId, draftId);
        int[] nextOrder = { photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId).stream()
                .mapToInt(ScanDraftPhoto::getSortOrder).max().orElse(-1) + 1 };
        photos.forEach(p -> {
            byte[] bytes = Base64.getDecoder().decode(p.getImageBase64());
            String key = storageService.store(bytes, safeMimeType(p.getImageMimeType()));
            photoRepository.save(ScanDraftPhoto.builder().draftId(draft.getId()).storageKey(key).angle(p.getAngle()).sortOrder(nextOrder[0]++).build());
        });
        return toDraftResponse(draft, photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId), userId);
    }

    /** Re-runs AI vision against everything currently attached to the draft (excluding REFERENCE
     * angle entries — fetched stock/online images, not the reviewer's own copy, so they'd muddy
     * edition-specific details vision might otherwise pick up). Read-only: returns suggestions for
     * the caller to apply via updateDraft, the same review-before-apply shape the capture flow's
     * own live merge preview already uses. */
    @Transactional(readOnly = true)
    public ExtractResponse reextractDraft(Long sessionId, Long draftId, String hint, Long userId) {
        findSessionOrThrow(sessionId, userId);
        findDraftOrThrow(sessionId, draftId);
        List<ScanDraftPhoto> photos = photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(draftId).stream()
                .filter(p -> !"REFERENCE".equals(p.getAngle()))
                .toList();
        if (photos.isEmpty()) {
            return ExtractResponse.builder().visionAvailable(false).notes("No photos to analyse yet — add some first.").build();
        }
        ExtractRequest req = new ExtractRequest();
        req.setImagesBase64(photos.stream().map(this::loadPhotoAsBase64).toList());
        req.setHint(hint);
        return visualScanService.extract(req);
    }

    private String loadPhotoAsBase64(ScanDraftPhoto photo) {
        try {
            org.springframework.core.io.Resource resource = storageService.load(photo.getStorageKey());
            return Base64.getEncoder().encodeToString(resource.getInputStream().readAllBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Transactional
    public ScanDraftResponse mergeDrafts(Long sessionId, ScanDraftMergeRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);
        if (req.getPrimaryDraftId().equals(req.getSecondaryDraftId())) {
            throw new ConflictException("Cannot merge a draft with itself");
        }
        ScanDraft primary = findDraftOrThrow(sessionId, req.getPrimaryDraftId());
        ScanDraft secondary = findDraftOrThrow(sessionId, req.getSecondaryDraftId());

        // Keep primary's fields; move secondary's photos onto primary; drop secondary. Both photo
        // lists are already fully loaded here, so the final response is assembled in memory rather
        // than re-querying what we just wrote.
        List<ScanDraftPhoto> primaryPhotos = photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(primary.getId());
        List<ScanDraftPhoto> secondaryPhotos = photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(secondary.getId());
        secondaryPhotos.forEach(p -> p.setDraftId(primary.getId()));
        photoRepository.saveAll(secondaryPhotos);
        draftRepository.delete(secondary);

        List<ScanDraftPhoto> allPhotos = new ArrayList<>(primaryPhotos);
        allPhotos.addAll(secondaryPhotos);
        return toDraftResponse(primary, allPhotos, userId);
    }

    /**
     * Ownership check for GET /media/{key} — a photo may only be served back to the user who
     * owns the session it was captured in, not any authenticated user who obtains the key.
     */
    @Transactional(readOnly = true)
    public void assertPhotoAccessible(String storageKey, Long userId) {
        ScanDraftPhoto photo = photoRepository.findByStorageKey(storageKey)
                .orElseThrow(() -> new NotFoundException("Photo not found"));
        ScanDraft draft = draftRepository.findById(photo.getDraftId())
                .orElseThrow(() -> new NotFoundException("Photo not found"));
        ScanSession session = sessionRepository.findById(draft.getSessionId())
                .orElseThrow(() -> new NotFoundException("Photo not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenException("Photo not found");
        }
    }

    // --- helpers ---

    private ScanSession findSessionOrThrow(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Scan session not found: " + sessionId));
    }

    private ScanDraft findDraftOrThrow(Long sessionId, Long draftId) {
        return draftRepository.findByIdAndSessionId(draftId, sessionId)
                .orElseThrow(() -> new NotFoundException("Draft not found: " + draftId));
    }

    /** Falls back to image/jpeg on a missing/malformed MIME type. */
    private String safeMimeType(String mime) {
        return visualScanService.parseMime(mime).toString();
    }

    private ScanSessionResponse toSessionResponse(ScanSession s, Collection collection, long pendingDraftCount) {
        return ScanSessionResponse.builder()
                .id(s.getId())
                .collectionId(s.getCollectionId())
                .collectionName(collection != null ? collection.getName() : null)
                .status(s.getStatus())
                .pendingDraftCount(pendingDraftCount)
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private ScanDraftResponse toDraftResponse(ScanDraft d, List<ScanDraftPhoto> photos, Long userId) {
        return ScanDraftResponse.builder()
                .id(d.getId())
                .sessionId(d.getSessionId())
                .status(d.getStatus())
                .matchKind(d.getMatchKind())
                .existingItemId(d.getExistingItemId())
                .duplicateOfDraftId(d.getDuplicateOfDraftId())
                .barcode(d.getBarcode())
                .category(d.getCategory())
                .format(d.getFormat())
                .title(d.getTitle())
                .subtitle(d.getSubtitle())
                .description(d.getDescription())
                .coverUrl(d.getCoverUrl())
                .releaseYear(d.getReleaseYear())
                .publisher(d.getPublisher())
                .metadata(d.getMetadata())
                .confidence(d.getConfidence())
                .externalSource(d.getExternalSource())
                .photos(photos.stream().map(p -> ScanDraftResponse.Photo.builder()
                        .id(p.getId())
                        .url(storageService.publicUrl(p.getStorageKey()))
                        .angle(p.getAngle())
                        .build()).toList())
                .relatedEditions(findRelatedEditions(d, userId))
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    /**
     * Other items the user already owns with the same (normalized) title — "you're scanning a
     * Blu-ray of a movie you already have on DVD" is exactly the situation collectors need
     * flagged, and no barcode source can ever tell you that (different edition = different
     * barcode). Purely informational: never blocks anything, just surfaces it on the draft so the
     * decision to buy another edition anyway stays a deliberate one instead of an accident.
     * Excludes the item this exact draft already resolves to (existingItemId) — that's "the same
     * copy", not "another edition", and is already surfaced via matchKind=ALREADY_OWNED.
     */
    private List<ScanDraftResponse.RelatedEdition> findRelatedEditions(ScanDraft d, Long userId) {
        if (d.getTitle() == null || d.getTitle().isBlank()) return List.of();
        String normalized = normalizeTitle(d.getTitle());
        if (normalized.isBlank()) return List.of();

        return itemRepository.findOwnedByNormalizedTitle(userId, normalized, List.of()).stream()
                .filter(i -> !i.getId().equals(d.getExistingItemId()))
                .map(i -> ScanDraftResponse.RelatedEdition.builder()
                        .itemId(i.getId())
                        .title(i.getTitle())
                        .format(i.getFormat())
                        .releaseYear(i.getReleaseYear())
                        .build())
                .toList();
    }

    private static String normalizeTitle(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
