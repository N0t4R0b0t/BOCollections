package com.bocollections.backend.service;

import com.bocollections.backend.dto.ExtractRequest;
import com.bocollections.backend.dto.ExtractResponse;
import com.bocollections.backend.dto.ScanSessionStatusRequest;
import com.bocollections.backend.dto.TasteProfile;
import com.bocollections.backend.dto.thrift.*;
import com.bocollections.backend.entity.Confidence;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.OwnedStatus;
import com.bocollections.backend.entity.ThriftSession;
import com.bocollections.backend.entity.ThriftSighting;
import com.bocollections.backend.entity.ThriftSightingPhoto;
import com.bocollections.backend.entity.ThriftSourceMode;
import com.bocollections.backend.exception.ForbiddenException;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.ThriftSessionRepository;
import com.bocollections.backend.repository.ThriftSightingCountProjection;
import com.bocollections.backend.repository.ThriftSightingPhotoRepository;
import com.bocollections.backend.repository.ThriftSightingRepository;
import com.bocollections.backend.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns thrift-session/sighting persistence — ThriftService stays pure identification/classification
 * (no persistence), same role split as MetadataLookupService/VisualScanService relative to
 * ScanSessionService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThriftSessionService {

    private final ThriftSessionRepository sessionRepository;
    private final ThriftSightingRepository sightingRepository;
    private final ThriftSightingPhotoRepository photoRepository;
    private final ThriftService thriftService;
    private final VisualScanService visualScanService;
    private final TasteProfileService tasteProfileService;
    private final StorageService storageService;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public ThriftSessionResponse createSession(ThriftSessionRequest req, Long userId) {
        ThriftSession session = ThriftSession.builder()
                .userId(userId)
                .location(req.getLocation())
                .build();
        session = sessionRepository.save(session);
        return toSessionResponse(session, 0);
    }

    @Transactional(readOnly = true)
    public List<ThriftSessionResponse> listSessions(Long userId) {
        List<ThriftSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (sessions.isEmpty()) return List.of();

        Set<Long> ids = sessions.stream().map(ThriftSession::getId).collect(Collectors.toSet());
        Map<Long, Long> countMap = sightingRepository.countsBySessionIds(ids).stream()
                .collect(Collectors.toMap(ThriftSightingCountProjection::getSessionId, ThriftSightingCountProjection::getCount));

        return sessions.stream().map(s -> toSessionResponse(s, countMap.getOrDefault(s.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public ThriftSessionResponse getSession(Long sessionId, Long userId) {
        ThriftSession session = findSessionOrThrow(sessionId, userId);
        long count = sightingRepository.countsBySessionIds(Set.of(sessionId)).stream()
                .findFirst().map(ThriftSightingCountProjection::getCount).orElse(0L);
        return toSessionResponse(session, count);
    }

    @Transactional
    public ThriftSessionResponse updateSessionStatus(Long sessionId, ScanSessionStatusRequest req, Long userId) {
        ThriftSession session = findSessionOrThrow(sessionId, userId);
        session.setStatus(req.getStatus());
        sessionRepository.save(session);
        return getSession(sessionId, userId);
    }

    @Transactional
    public void discardSession(Long sessionId, Long userId) {
        ThriftSession session = findSessionOrThrow(sessionId, userId);
        List<Long> sightingIds = sightingRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .map(ThriftSighting::getId).toList();
        photoRepository.findBySightingIdIn(sightingIds).forEach(p -> storageService.delete(p.getStorageKey()));
        sessionRepository.delete(session); // cascades sightings (and photos, via sightings' own FK)
    }

    @Transactional(readOnly = true)
    public List<ThriftSightingResponse> listSightings(Long sessionId, Long userId) {
        findSessionOrThrow(sessionId, userId);
        return toSightingResponses(sightingRepository.findBySessionIdOrderByCreatedAtDesc(sessionId));
    }

    @Transactional(readOnly = true)
    public List<ThriftSightingResponse> searchSightings(Long userId, String q) {
        return toSightingResponses(sightingRepository.search(userId, q));
    }

    /** Batches the photo-gallery lookup across every sighting being rendered — one query instead
     * of one per row, same N+1 avoidance as CollectionService.getEntries. */
    private List<ThriftSightingResponse> toSightingResponses(List<ThriftSighting> sightings) {
        if (sightings.isEmpty()) return List.of();
        Set<Long> ids = sightings.stream().map(ThriftSighting::getId).collect(Collectors.toSet());
        Map<Long, List<ThriftSightingResponse.Photo>> photosBySighting = photoRepository.findBySightingIdIn(ids).stream()
                .collect(Collectors.groupingBy(ThriftSightingPhoto::getSightingId, Collectors.mapping(this::toPhoto, Collectors.toList())));
        return sightings.stream()
                .map(s -> toSightingResponse(s, photosBySighting.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    private ThriftSightingResponse.Photo toPhoto(ThriftSightingPhoto p) {
        return ThriftSightingResponse.Photo.builder()
                .id(p.getId())
                .url(storageService.publicUrl(p.getStorageKey()))
                .bboxX(p.getBboxX()).bboxY(p.getBboxY()).bboxW(p.getBboxW()).bboxH(p.getBboxH())
                .build();
    }

    @Transactional
    public ThriftScanResponse runShelfScan(Long sessionId, ThriftScanRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ThriftScanResponse response = thriftService.scan(req, userId);

        // Only store the shelf photo if something was actually identified — otherwise a
        // blurry/empty-shelf scan uploads a photo that no sighting ever references, and it's
        // never reachable by discardSession()'s cleanup (which only walks photos via sightings).
        if (!response.getItems().isEmpty()) {
            String photoKey = storePhoto(req.getImageBase64(), req.getImageMimeType());
            for (ThriftItem item : response.getItems()) {
                recordSighting(sessionId, userId, item.getTitle(), item.getCategory(), item.getFormat(),
                        item.getArtistOrAuthor(), null, null, item.getOwnedStatus(), item.getItemId(),
                        item.getConfidence(), photoKey, ThriftSourceMode.SHELF, item.getBbox());
            }
        }
        return response;
    }

    /**
     * Shelf mode's shoot-then-analyze flow: one or more shots taken this pass, each run through
     * the same per-photo scan/cross-reference/record path runShelfScan uses for a single photo,
     * looped here. The existing same-session-title merge in recordSighting/updateExistingSighting
     * already does the heavy lifting for "the same item spotted in two overlapping shots" — it
     * just gets called once per (photo, detected item) pair instead of once, so a title seen in
     * photo 1 and photo 2 naturally ends up as one ThriftSighting with two ThriftSightingPhoto
     * rows (one per photo, each carrying that photo's own bbox) rather than needing a separate
     * join entity. Returns sightings touched by this analyze pass, ranked by matchScore desc.
     */
    @Transactional
    public List<ThriftSightingResponse> analyzeShelf(Long sessionId, ThriftShelfAnalyzeRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);
        TasteProfile profile = tasteProfileService.getOrCompute(userId);

        Set<Long> touchedIds = new LinkedHashSet<>();
        for (ThriftShelfAnalyzeRequest.PhotoInput photo : req.getPhotos()) {
            ThriftScanRequest scanReq = new ThriftScanRequest();
            scanReq.setImageBase64(photo.getImageBase64());
            scanReq.setImageMimeType(photo.getImageMimeType());
            scanReq.setCollectionIds(req.getCollectionIds());
            ThriftScanResponse response = thriftService.scan(scanReq, userId);
            if (response.getItems().isEmpty()) continue;

            String photoKey = storePhoto(photo.getImageBase64(), photo.getImageMimeType());
            for (ThriftItem item : response.getItems()) {
                Long sightingId = recordSighting(sessionId, userId, item.getTitle(), item.getCategory(), item.getFormat(),
                        item.getArtistOrAuthor(), null, null, item.getOwnedStatus(), item.getItemId(),
                        item.getConfidence(), photoKey, ThriftSourceMode.SHELF, item.getBbox());
                touchedIds.add(sightingId);
            }
        }
        if (touchedIds.isEmpty()) return List.of();

        List<ThriftSighting> touched = sightingRepository.findAllById(touchedIds);
        for (ThriftSighting sighting : touched) {
            double score = switch (sighting.getOwnedStatus()) {
                // A confirmed catalogue match is a stronger signal than the taste-profile
                // heuristic could ever produce — rank it above any purely-heuristic score.
                case OWNED, DIFFERENT_VERSION -> 1.0;
                case NOT_OWNED, INTERESTING -> tasteProfileService.score(
                        profile, sighting.getCategory(), sighting.getFormat(), sighting.getPublisher(), sighting.getReleaseYear());
            };
            sighting.setMatchScore(score);
        }
        sightingRepository.saveAll(touched);

        return toSightingResponses(touched).stream()
                .sorted(Comparator.comparing(ThriftSightingResponse::getMatchScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public ThriftClassifyResponse classifyHeldItem(Long sessionId, ThriftClassifyRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ThriftService.ClassificationResult result = thriftService.classifyItem(
                req.getTitle(), req.getCategory(), req.getFormat(), req.getPublisher(), req.getReleaseYear(),
                req.getExistingItemId(), req.getOwnedInCollections(), req.getCollectionIds(), userId);

        String photoKey = req.getImageBase64() != null ? storePhoto(req.getImageBase64(), req.getImageMimeType()) : null;

        recordSighting(sessionId, userId, req.getTitle(), req.getCategory(), req.getFormat(),
                null, req.getPublisher(), req.getReleaseYear(), result.ownedStatus(), result.itemId(),
                req.getConfidence(), photoKey, ThriftSourceMode.HELD_ITEM, null);

        return ThriftClassifyResponse.builder().ownedStatus(result.ownedStatus()).itemId(result.itemId()).build();
    }

    /**
     * On a same-session title collision, updates the existing row instead of inserting a
     * duplicate — the newest classification wins (e.g. a later barcode-confirmed OWNED should
     * overwrite an earlier fuzzy-matched NOT_OWNED for the same title this trip, not be silently
     * discarded in favor of just bumping a counter).
     */
    private Long recordSighting(
            Long sessionId, Long userId, String title, MediaCategory category,
            String format, String artistOrAuthor, String publisher, Integer releaseYear,
            OwnedStatus ownedStatus, Long itemId, Confidence confidence,
            String photoStorageKey, ThriftSourceMode sourceMode, ThriftItem.BoundingBox bbox) {

        String normalized = thriftService.normalizeTitle(title);
        Optional<ThriftSighting> existing = sightingRepository.findBySessionIdAndNormalizedTitle(sessionId, normalized);

        if (existing.isPresent()) {
            return updateExistingSighting(existing.get(), category, format, artistOrAuthor, publisher,
                    releaseYear, ownedStatus, itemId, confidence, photoStorageKey, bbox);
        }

        ThriftSighting sighting = ThriftSighting.builder()
                .sessionId(sessionId)
                .userId(userId)
                .title(title)
                .normalizedTitle(normalized)
                .category(category)
                .format(format)
                .artistOrAuthor(artistOrAuthor)
                .publisher(publisher)
                .releaseYear(releaseYear)
                .ownedStatus(ownedStatus)
                .itemId(itemId)
                .confidence(confidence)
                .sourceMode(sourceMode)
                .build();
        if (tryInsert(sighting)) {
            if (photoStorageKey != null) {
                photoRepository.save(toPhotoEntity(sighting.getId(), photoStorageKey, bbox));
            }
            return sighting.getId();
        } else {
            // Lost a race against another request inserting the same (session, normalized title)
            // between our existence check and this insert — fall back to the update path so we
            // bump times_seen on the winner's row instead of leaving this scan's result stranded.
            ThriftSighting winner = sightingRepository.findBySessionIdAndNormalizedTitle(sessionId, normalized)
                    .orElseThrow(() -> new IllegalStateException(
                            "Sighting insert conflicted but no row found for session " + sessionId + ", title " + normalized));
            return updateExistingSighting(winner, category, format, artistOrAuthor, publisher,
                    releaseYear, ownedStatus, itemId, confidence, photoStorageKey, bbox);
        }
    }

    private ThriftSightingPhoto toPhotoEntity(Long sightingId, String storageKey, ThriftItem.BoundingBox bbox) {
        ThriftSightingPhoto.ThriftSightingPhotoBuilder builder = ThriftSightingPhoto.builder()
                .sightingId(sightingId).storageKey(storageKey);
        if (bbox != null) {
            builder.bboxX(bbox.getX()).bboxY(bbox.getY()).bboxW(bbox.getW()).bboxH(bbox.getH());
        }
        return builder.build();
    }

    /**
     * Attempts the insert in its own fully independent transaction (PROPAGATION_REQUIRES_NEW)
     * rather than directly in the ambient one. Postgres aborts an entire transaction after any
     * single statement fails, so without isolating this attempt, a lost unique-constraint race
     * here would also break every other sighting this same request is trying to record (e.g. the
     * rest of a multi-item shelf scan).
     * <p>
     * REQUIRES_NEW rather than NESTED (a savepoint within the same transaction) deliberately —
     * confirmed live against the real Hibernate/Postgres stack that Spring's JpaTransactionManager
     * doesn't support savepoint-based nested transactions at all (neither the default nor the
     * Hibernate-specific JpaDialect implements supportsSavepoints()), so NESTED throws
     * unconditionally regardless of configuration. A separate transaction (its own connection,
     * suspending the ambient one for its duration) achieves the same isolation goal without
     * needing savepoint support, at the cost of one extra pooled connection borrow per insert
     * attempt — acceptable given this only runs on the (rare) unique-constraint-race path, not
     * every insert.
     */
    private boolean tryInsert(ThriftSighting sighting) {
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            independent.executeWithoutResult(status -> sightingRepository.saveAndFlush(sighting));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * OWNED &gt; DIFFERENT_VERSION &gt; INTERESTING &gt; NOT_OWNED. A later, weaker re-scan of the
     * same title this trip (e.g. a vague shelf glance after an earlier barcode-confirmed match)
     * must not silently erase the stronger earlier result.
     */
    private int tierRank(OwnedStatus status) {
        return switch (status) {
            case OWNED -> 3;
            case DIFFERENT_VERSION -> 2;
            case INTERESTING -> 1;
            case NOT_OWNED -> 0;
        };
    }

    private Long updateExistingSighting(
            ThriftSighting sighting, MediaCategory category, String format, String artistOrAuthor,
            String publisher, Integer releaseYear, OwnedStatus ownedStatus, Long itemId,
            Confidence confidence, String photoStorageKey, ThriftItem.BoundingBox bbox) {
        sighting.setTimesSeen(sighting.getTimesSeen() + 1);
        sighting.setLastSeenAt(LocalDateTime.now());
        // Only overwrite ownedStatus/itemId when the new result is at least as strong as what's
        // already recorded, or it carries a real catalogue link the existing row doesn't have —
        // an exact item match always beats a fuzzy title guess regardless of tier. Otherwise leave
        // the stronger earlier result alone; timesSeen/lastSeenAt above still update either way.
        boolean strongerOrEqual = tierRank(ownedStatus) >= tierRank(sighting.getOwnedStatus());
        boolean gainsRealLink = itemId != null && sighting.getItemId() == null;
        if (strongerOrEqual || gainsRealLink) {
            sighting.setOwnedStatus(ownedStatus);
            sighting.setItemId(itemId);
        }
        if (category != null) sighting.setCategory(category);
        if (format != null) sighting.setFormat(format);
        if (artistOrAuthor != null) sighting.setArtistOrAuthor(artistOrAuthor);
        if (publisher != null) sighting.setPublisher(publisher);
        if (releaseYear != null) sighting.setReleaseYear(releaseYear);
        if (confidence != null) sighting.setConfidence(confidence);
        sightingRepository.save(sighting);
        if (photoStorageKey != null) {
            photoRepository.save(toPhotoEntity(sighting.getId(), photoStorageKey, bbox));
        }
        return sighting.getId();
    }

    private String storePhoto(String imageBase64, String imageMimeType) {
        try {
            byte[] bytes = Base64.getDecoder().decode(imageBase64);
            return storageService.store(bytes, imageMimeType != null ? imageMimeType : "image/jpeg");
        } catch (Exception e) {
            log.warn("Failed to store thrift photo: {}", e.getMessage());
            return null;
        }
    }

    private ThriftSession findSessionOrThrow(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Thrift session not found: " + sessionId));
    }

    private ThriftSessionResponse toSessionResponse(ThriftSession s, long sightingCount) {
        return ThriftSessionResponse.builder()
                .id(s.getId())
                .location(s.getLocation())
                .status(s.getStatus())
                .sightingCount(sightingCount)
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private ThriftSightingResponse toSightingResponse(ThriftSighting s) {
        List<ThriftSightingResponse.Photo> photos = photoRepository.findBySightingId(s.getId()).stream()
                .map(this::toPhoto).toList();
        return toSightingResponse(s, photos);
    }

    private ThriftSightingResponse toSightingResponse(ThriftSighting s, List<ThriftSightingResponse.Photo> photos) {
        return ThriftSightingResponse.builder()
                .id(s.getId())
                .sessionId(s.getSessionId())
                .title(s.getTitle())
                .category(s.getCategory())
                .format(s.getFormat())
                .artistOrAuthor(s.getArtistOrAuthor())
                .publisher(s.getPublisher())
                .releaseYear(s.getReleaseYear())
                .ownedStatus(s.getOwnedStatus())
                .itemId(s.getItemId())
                .confidence(s.getConfidence())
                .photos(photos)
                .sourceMode(s.getSourceMode())
                .timesSeen(s.getTimesSeen())
                .matchScore(s.getMatchScore())
                .lastSeenAt(s.getLastSeenAt())
                .createdAt(s.getCreatedAt())
                .build();
    }

    // --- Sighting photo gallery + re-extraction ---

    /** Appends photos to an existing sighting's gallery — for filling in more evidence during
     * at-home trip review, never part of the in-store capture loop itself. */
    @Transactional
    public ThriftSightingResponse addSightingPhotos(Long sessionId, Long sightingId, List<ThriftSightingPhotoInput> photos, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ThriftSighting sighting = findSightingOrThrow(sessionId, sightingId);
        photos.forEach(p -> {
            byte[] bytes = Base64.getDecoder().decode(p.getImageBase64());
            String key = storageService.store(bytes, visualScanService.parseMime(p.getImageMimeType()).toString());
            photoRepository.save(ThriftSightingPhoto.builder().sightingId(sighting.getId()).storageKey(key).build());
        });
        return toSightingResponse(sighting);
    }

    @Transactional
    public ThriftSightingResponse deleteSightingPhoto(Long sessionId, Long sightingId, Long photoId, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ThriftSighting sighting = findSightingOrThrow(sessionId, sightingId);
        ThriftSightingPhoto photo = photoRepository.findById(photoId)
                .filter(p -> p.getSightingId().equals(sightingId))
                .orElseThrow(() -> new NotFoundException("Photo not found: " + photoId));
        storageService.delete(photo.getStorageKey());
        photoRepository.delete(photo);
        return toSightingResponse(sighting);
    }

    /** Re-runs AI vision against everything currently in the sighting's gallery — read-only,
     * returns a suggestion the caller applies via updateSighting, same pattern as
     * ItemService.reextract/ScanSessionService.reextractDraft. Meant for at-home review with more
     * time to look things over, not the fast in-store capture loop. */
    @Transactional(readOnly = true)
    public ExtractResponse reextractSighting(Long sessionId, Long sightingId, String hint, Long userId) {
        findSessionOrThrow(sessionId, userId);
        List<ThriftSightingPhoto> photos = photoRepository.findBySightingId(sightingId);
        if (photos.isEmpty()) {
            return ExtractResponse.builder().visionAvailable(false).notes("No photos to analyse yet — add some first.").build();
        }
        ExtractRequest req = new ExtractRequest();
        req.setImagesBase64(photos.stream().map(this::loadPhotoAsBase64).toList());
        req.setHint(hint);
        return visualScanService.extract(req);
    }

    /** Applies a manual correction or an accepted reextract() suggestion — only non-null fields
     * are written, never overwrites ownedStatus/itemId (that's cross-reference logic's job, not
     * something a human/vision-suggestion edit should override directly). */
    @Transactional
    public ThriftSightingResponse updateSighting(Long sessionId, Long sightingId, ThriftSightingUpdateRequest req, Long userId) {
        findSessionOrThrow(sessionId, userId);
        ThriftSighting sighting = findSightingOrThrow(sessionId, sightingId);
        if (req.getTitle() != null) {
            sighting.setTitle(req.getTitle());
            sighting.setNormalizedTitle(thriftService.normalizeTitle(req.getTitle()));
        }
        if (req.getCategory() != null) sighting.setCategory(req.getCategory());
        if (req.getFormat() != null) sighting.setFormat(req.getFormat());
        if (req.getArtistOrAuthor() != null) sighting.setArtistOrAuthor(req.getArtistOrAuthor());
        if (req.getPublisher() != null) sighting.setPublisher(req.getPublisher());
        if (req.getReleaseYear() != null) sighting.setReleaseYear(req.getReleaseYear());
        sightingRepository.save(sighting);
        return toSightingResponse(sighting);
    }

    /** Ownership check for GET /media/{key} on a thrift sighting photo — mirrors
     * ScanSessionService.assertPhotoAccessible. Sighting photos are user-owned (unlike item
     * photos, which are shared catalogue data), so they stay behind this check rather than the
     * shared-access path MediaController gives item photos. */
    @Transactional(readOnly = true)
    public void assertSightingPhotoAccessible(String storageKey, Long userId) {
        ThriftSightingPhoto photo = photoRepository.findFirstByStorageKey(storageKey)
                .orElseThrow(() -> new NotFoundException("Photo not found"));
        ThriftSighting sighting = sightingRepository.findById(photo.getSightingId())
                .orElseThrow(() -> new NotFoundException("Photo not found"));
        if (!sighting.getUserId().equals(userId)) {
            throw new ForbiddenException("Photo not found");
        }
    }

    private String loadPhotoAsBase64(ThriftSightingPhoto photo) {
        try {
            org.springframework.core.io.Resource resource = storageService.load(photo.getStorageKey());
            return Base64.getEncoder().encodeToString(resource.getInputStream().readAllBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private ThriftSighting findSightingOrThrow(Long sessionId, Long sightingId) {
        return sightingRepository.findById(sightingId)
                .filter(s -> s.getSessionId().equals(sessionId))
                .orElseThrow(() -> new NotFoundException("Sighting not found: " + sightingId));
    }
}
