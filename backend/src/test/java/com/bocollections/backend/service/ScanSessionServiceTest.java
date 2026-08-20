package com.bocollections.backend.service;

import com.bocollections.backend.dto.*;
import com.bocollections.backend.entity.Collection;
import com.bocollections.backend.entity.MatchKind;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.ScanDraft;
import com.bocollections.backend.entity.ScanDraftPhoto;
import com.bocollections.backend.entity.ScanDraftStatus;
import com.bocollections.backend.entity.ScanSession;
import com.bocollections.backend.entity.ScanSessionStatus;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.CollectionRepository;
import com.bocollections.backend.repository.ItemPhotoRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.repository.ScanDraftPhotoRepository;
import com.bocollections.backend.repository.ScanDraftRepository;
import com.bocollections.backend.repository.ScanSessionRepository;
import com.bocollections.backend.service.lookup.MetadataLookupService;
import com.bocollections.backend.service.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the bulk-scan-mode session/draft lifecycle. All collaborators are mocked —
 * this exercises ScanSessionService's own logic (duplicate flagging, title-search fallback,
 * approve/merge/discard bookkeeping), not persistence itself.
 */
@ExtendWith(MockitoExtension.class)
class ScanSessionServiceTest {

    @Mock private ScanSessionRepository sessionRepository;
    @Mock private ScanDraftRepository draftRepository;
    @Mock private ScanDraftPhotoRepository photoRepository;
    @Mock private ItemPhotoRepository itemPhotoRepository;
    @Mock private CollectionRepository collectionRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private ItemService itemService;
    @Mock private CollectionService collectionService;
    @Mock private StorageService storageService;
    @Mock private MetadataLookupService metadataLookupService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private VisualScanService visualScanService;

    private ScanSessionService service;
    private final AtomicLong idSeq = new AtomicLong(1);

    private static final Long USER_ID = 42L;
    private static final Long SESSION_ID = 7L;
    private static final Long COLLECTION_ID = 3L;

    @BeforeEach
    void setUp() {
        service = new ScanSessionService(
                sessionRepository, draftRepository, photoRepository, itemPhotoRepository, collectionRepository,
                itemRepository, itemService, collectionService, storageService, metadataLookupService,
                transactionManager, visualScanService, new ObjectMapper());

        // Every draft response now looks up related editions by title — irrelevant to most tests,
        // default to "none found" so they don't have to stub it individually.
        lenient().when(itemRepository.findOwnedByNormalizedTitle(any(), any(), any())).thenReturn(List.of());

        // Simulate DB-assigned IDs on save. Not every test exercises these — lenient() avoids
        // strict-stubbing failures on the tests that don't touch drafts/photos at all.
        lenient().when(draftRepository.save(any(ScanDraft.class))).thenAnswer(inv -> {
            ScanDraft d = inv.getArgument(0);
            if (d.getId() == null) d.setId(idSeq.getAndIncrement());
            return d;
        });
        lenient().when(photoRepository.save(any(ScanDraftPhoto.class))).thenAnswer(inv -> {
            ScanDraftPhoto p = inv.getArgument(0);
            if (p.getId() == null) p.setId(idSeq.getAndIncrement());
            return p;
        });
        // createDraft persists via a real TransactionTemplate wrapping this mocked manager —
        // give it a non-null status so execute() actually runs the callback.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().when(visualScanService.parseMime(any())).thenReturn(MimeTypeUtils.IMAGE_JPEG);
    }

    private ScanSession existingSession() {
        return ScanSession.builder().id(SESSION_ID).userId(USER_ID).collectionId(COLLECTION_ID).status(ScanSessionStatus.OPEN).build();
    }

    private void stubSessionFound() {
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(existingSession()));
    }

    @Test
    void createDraft_flagsSameSessionBarcodeReplay() {
        stubSessionFound();

        ScanDraftRequest first = new ScanDraftRequest();
        first.setMatchKind(MatchKind.CONFIDENT);
        first.setBarcode("111222333");
        first.setTitle("First Copy");
        ScanDraftResponse firstSaved = service.createDraft(SESSION_ID, first, USER_ID);

        when(draftRepository.findFirstBySessionIdAndBarcodeOrderByCreatedAtAsc(SESSION_ID, "111222333"))
                .thenReturn(Optional.of(ScanDraft.builder().id(firstSaved.getId()).sessionId(SESSION_ID).barcode("111222333").matchKind(MatchKind.CONFIDENT).build()));

        ScanDraftRequest second = new ScanDraftRequest();
        second.setMatchKind(MatchKind.CONFIDENT);
        second.setBarcode("111222333");
        second.setTitle("Second Copy — same barcode");

        ScanDraftResponse secondSaved = service.createDraft(SESSION_ID, second, USER_ID);

        assertThat(secondSaved.getDuplicateOfDraftId()).isEqualTo(firstSaved.getId());
    }

    @Test
    void createDraft_alreadyOwned_isCreatedAsSkipped() {
        stubSessionFound();

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.ALREADY_OWNED);
        req.setBarcode("999");
        req.setTitle("Owned already");

        ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

        assertThat(saved.getStatus()).isEqualTo(ScanDraftStatus.SKIPPED);
    }

    @Test
    void createDraft_unmatchedWithNoCover_fillsGenericImageryFromTitleSearch() {
        stubSessionFound();
        LookupResult found = LookupResult.builder()
                .source("OPEN_LIBRARY")
                .coverUrl("https://covers.example/generic.jpg")
                .publisher("Some Publisher")
                .releaseYear(1999)
                .build();
        when(metadataLookupService.lookupByTitle("Mystery Book", MediaCategory.PRINT)).thenReturn(Optional.of(found));

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.UNMATCHED);
        req.setCategory(MediaCategory.PRINT);
        req.setTitle("Mystery Book");
        // no barcode, no coverUrl — should trigger the title-search fallback

        ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

        assertThat(saved.getCoverUrl()).isEqualTo("https://covers.example/generic.jpg");
        assertThat(saved.getPublisher()).isEqualTo("Some Publisher");
        assertThat(saved.getReleaseYear()).isEqualTo(1999);
        verify(metadataLookupService).lookupByTitle("Mystery Book", MediaCategory.PRINT);
    }

    @Test
    void createDraft_confidentMatch_doesNotTriggerTitleSearch() {
        stubSessionFound();

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.CONFIDENT);
        req.setBarcode("123456");
        req.setTitle("Known Item");
        req.setCoverUrl("https://covers.example/known.jpg");

        service.createDraft(SESSION_ID, req, USER_ID);

        verifyNoInteractions(metadataLookupService);
    }

    // Regression coverage: previously the title-search fallback only ran when draft.barcode was
    // null, so a barcode that scanned but failed to resolve (e.g. UPCitemdb's free-tier rate
    // limit) silently gave up on imagery entirely even though vision had already read a title off
    // the box. The fallback must fire whenever coverUrl is still blank, barcode or not.
    @Test
    void createDraft_barcodeScannedButUnresolved_stillTriggersTitleSearchFallback() {
        stubSessionFound();
        LookupResult found = LookupResult.builder()
                .source("TMDB")
                .coverUrl("https://covers.example/dredd.jpg")
                .build();
        when(metadataLookupService.lookupByTitle("Dredd", MediaCategory.VIDEO)).thenReturn(Optional.of(found));

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.UNMATCHED);
        req.setCategory(MediaCategory.VIDEO);
        req.setBarcode("786936144505"); // scanned, but barcode lookup came back with no cover
        req.setTitle("Dredd");

        ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

        assertThat(saved.getCoverUrl()).isEqualTo("https://covers.example/dredd.jpg");
        verify(metadataLookupService).lookupByTitle("Dredd", MediaCategory.VIDEO);
    }

    @Test
    void createDraft_storesPhotosViaStorageService() {
        stubSessionFound();
        when(storageService.store(any(), eq("image/jpeg"))).thenReturn("abc-123.jpg");
        when(storageService.publicUrl("abc-123.jpg")).thenReturn("/media/abc-123.jpg");

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.MANUAL);
        req.setTitle("Photographed item");
        ScanDraftPhotoRequest photo = new ScanDraftPhotoRequest();
        photo.setImageBase64(java.util.Base64.getEncoder().encodeToString("fake-bytes".getBytes()));
        photo.setImageMimeType("image/jpeg");
        photo.setAngle("FRONT");
        req.setPhotos(List.of(photo));

        ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

        assertThat(saved.getPhotos()).hasSize(1);
        assertThat(saved.getPhotos().get(0).getUrl()).isEqualTo("/media/abc-123.jpg");
        assertThat(saved.getPhotos().get(0).getAngle()).isEqualTo("FRONT");
    }

    // Exercises the real downloadAlternateImages() path against a local HTTP server, since it's
    // not mockable through a collaborator seam. posterOptions and physicalPhotos both point partly
    // at byte-identical images (the common case: retailer listings and TMDB alternates repeat the
    // same photo) — only the distinct ones should survive the content-hash dedup.
    @Test
    void createDraft_dedupsAlternateImagesByContentHash() throws Exception {
        stubSessionFound();
        byte[] coverBytes = "cover-bytes".getBytes();
        byte[] altBytes = "alternate-bytes".getBytes();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/cover.jpg", exchangeServing(coverBytes));
            server.createContext("/poster-a.jpg", exchangeServing(altBytes));
            server.createContext("/poster-b.jpg", exchangeServing(altBytes)); // byte-identical to poster-a
            server.createContext("/physical-a.jpg", exchangeServing(altBytes)); // byte-identical too
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            when(storageService.store(any(), any())).thenReturn("stored-key");
            when(storageService.publicUrl(any())).thenReturn("/media/stored-key");

            ScanDraftRequest req = new ScanDraftRequest();
            req.setMatchKind(MatchKind.CONFIDENT);
            req.setBarcode("786936144505");
            req.setTitle("Dredd");
            req.setCategory(MediaCategory.VIDEO);
            req.setCoverUrl(base + "/cover.jpg");
            req.setMetadata("""
                    {"posterOptions":["%s/poster-a.jpg","%s/poster-b.jpg"],"physicalPhotos":["%s/physical-a.jpg"]}
                    """.formatted(base, base, base));

            ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

            // 1 primary reference (cover.jpg) + 1 deduped alternate (poster-a/poster-b/physical-a
            // all collapse to the same content hash) — never 3 near-identical copies of the same photo.
            assertThat(saved.getPhotos()).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    // The exact-byte hash alone would miss this: the same visual cover re-encoded as PNG vs. JPEG
    // produces completely different bytes, but a perceptual (dHash) comparison still recognizes it
    // as the same photo — this is the actual complaint the perceptual pass exists to fix (retailer
    // listings and CDNs commonly re-serve the identical cover at a different size/compression).
    @Test
    void createDraft_dedupsVisuallyIdenticalImagesAcrossDifferentEncodings() throws Exception {
        stubSessionFound();
        // dHash needs actual texture/edges to produce a meaningful hash — flat solid-color blocks
        // degenerate to an all-zero hash for *any* uniform image regardless of color, which would
        // make this test pass for the wrong reason (or spuriously fail, as it did while writing
        // this: two different flat colors both hashed to 0). A diagonal gradient has real per-pixel
        // structure, closer to what actual cover art looks like.
        byte[] asPng = encode(diagonalGradient(false), "png");
        byte[] asJpeg = encode(diagonalGradient(false), "jpg"); // different bytes, same visual content
        byte[] genuinelyDifferent = encode(diagonalGradient(true), "png"); // inverted gradient

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/cover.png", exchangeServing(asPng));
            server.createContext("/same-cover.jpg", exchangeServing(asJpeg));
            server.createContext("/different.png", exchangeServing(genuinelyDifferent));
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            when(storageService.store(any(), any())).thenReturn("stored-key");
            when(storageService.publicUrl(any())).thenReturn("/media/stored-key");

            ScanDraftRequest req = new ScanDraftRequest();
            req.setMatchKind(MatchKind.CONFIDENT);
            req.setBarcode("786936144505");
            req.setTitle("Dredd");
            req.setCategory(MediaCategory.VIDEO);
            req.setCoverUrl(base + "/cover.png");
            req.setMetadata("""
                    {"posterOptions":["%s/same-cover.jpg"],"physicalPhotos":["%s/different.png"]}
                    """.formatted(base, base));

            ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

            // Primary reference (cover.png) + the genuinely different image — same-cover.jpg is
            // visually identical to the primary despite different bytes, so it's deduped out.
            assertThat(saved.getPhotos()).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    private static java.awt.image.BufferedImage diagonalGradient(boolean inverted) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int value = (x + y) * 255 / 126;
                if (inverted) value = 255 - value;
                image.setRGB(x, y, new java.awt.Color(value, value, value).getRGB());
            }
        }
        return image;
    }

    private static byte[] encode(java.awt.image.BufferedImage image, String format) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static com.sun.net.httpserver.HttpHandler exchangeServing(byte[] bytes) {
        return exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    // Regression coverage for the "you're scanning a Blu-ray of a movie you already own on DVD"
    // case — no barcode source can ever catch this (different edition = different barcode), so
    // it has to come from a title match against the user's existing catalogue.
    @Test
    void createDraft_flagsRelatedEditionOwnedUnderDifferentFormat() {
        stubSessionFound();
        com.bocollections.backend.entity.Item ownedDvd = com.bocollections.backend.entity.Item.builder()
                .id(500L).title("Dredd").format("DVD").releaseYear(2012).build();
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), eq("dredd"), eq(List.of())))
                .thenReturn(List.of(ownedDvd));

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.CONFIDENT);
        req.setTitle("Dredd");
        req.setFormat("Blu-ray");

        ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

        assertThat(saved.getRelatedEditions()).hasSize(1);
        assertThat(saved.getRelatedEditions().get(0).getItemId()).isEqualTo(500L);
        assertThat(saved.getRelatedEditions().get(0).getFormat()).isEqualTo("DVD");
    }

    @Test
    void createDraft_relatedEditions_excludesTheSameItemAlreadyResolvedByThisDraft() {
        stubSessionFound();
        com.bocollections.backend.entity.Item sameItem = com.bocollections.backend.entity.Item.builder()
                .id(500L).title("Dredd").format("Blu-ray").build();
        when(itemRepository.findOwnedByNormalizedTitle(eq(USER_ID), eq("dredd"), eq(List.of())))
                .thenReturn(List.of(sameItem));

        ScanDraftRequest req = new ScanDraftRequest();
        req.setMatchKind(MatchKind.ALREADY_OWNED);
        req.setTitle("Dredd");
        req.setExistingItemId(500L);

        ScanDraftResponse saved = service.createDraft(SESSION_ID, req, USER_ID);

        assertThat(saved.getRelatedEditions()).isEmpty();
    }

    @Test
    void approveDraft_createsNewItemWhenNoExistingItemId() {
        stubSessionFound();
        ScanDraft draft = ScanDraft.builder()
                .id(55L).sessionId(SESSION_ID).status(ScanDraftStatus.PENDING).matchKind(MatchKind.UNMATCHED)
                .category(MediaCategory.PRINT).format("Book").title("New Item").build();
        when(draftRepository.findByIdAndSessionId(55L, SESSION_ID)).thenReturn(Optional.of(draft));

        ItemResponse createdItem = ItemResponse.builder().id(900L).title("New Item").build();
        when(itemService.create(any(ItemRequest.class))).thenReturn(createdItem);

        when(collectionService.findEntryForItem(COLLECTION_ID, 900L, USER_ID)).thenReturn(Optional.empty());
        CollectionEntryResponse entryResponse = CollectionEntryResponse.builder().id(1L).collectionId(COLLECTION_ID).build();
        when(collectionService.addEntry(eq(COLLECTION_ID), any(CollectionEntryRequest.class), eq(USER_ID))).thenReturn(entryResponse);

        CollectionEntryResponse result = service.approveDraft(SESSION_ID, 55L, USER_ID);

        assertThat(result.getId()).isEqualTo(1L);
        ArgumentCaptor<ItemRequest> itemReqCaptor = ArgumentCaptor.forClass(ItemRequest.class);
        verify(itemService).create(itemReqCaptor.capture());
        assertThat(itemReqCaptor.getValue().getTitle()).isEqualTo("New Item");

        ArgumentCaptor<CollectionEntryRequest> entryReqCaptor = ArgumentCaptor.forClass(CollectionEntryRequest.class);
        verify(collectionService).addEntry(eq(COLLECTION_ID), entryReqCaptor.capture(), eq(USER_ID));
        assertThat(entryReqCaptor.getValue().getItemId()).isEqualTo(900L);

        ArgumentCaptor<ScanDraft> savedDraftCaptor = ArgumentCaptor.forClass(ScanDraft.class);
        verify(draftRepository, atLeastOnce()).save(savedDraftCaptor.capture());
        ScanDraft lastSaved = savedDraftCaptor.getAllValues().get(savedDraftCaptor.getAllValues().size() - 1);
        assertThat(lastSaved.getStatus()).isEqualTo(ScanDraftStatus.APPROVED);
        assertThat(lastSaved.getExistingItemId()).isEqualTo(900L);
    }

    @Test
    void approveDraft_reusesExistingItemIdWithoutCreatingNewItem() {
        stubSessionFound();
        ScanDraft draft = ScanDraft.builder()
                .id(56L).sessionId(SESSION_ID).status(ScanDraftStatus.PENDING).matchKind(MatchKind.CONFIDENT)
                .existingItemId(800L).title("Already catalogued").build();
        when(draftRepository.findByIdAndSessionId(56L, SESSION_ID)).thenReturn(Optional.of(draft));
        when(collectionService.findEntryForItem(COLLECTION_ID, 800L, USER_ID)).thenReturn(Optional.empty());
        when(collectionService.addEntry(eq(COLLECTION_ID), any(CollectionEntryRequest.class), eq(USER_ID)))
                .thenReturn(CollectionEntryResponse.builder().id(2L).build());

        service.approveDraft(SESSION_ID, 56L, USER_ID);

        verifyNoInteractions(itemService);
    }

    @Test
    void approveDraft_alreadyOwnedSkippedDraft_recoversExistingEntryInsteadOf409() {
        stubSessionFound();
        ScanDraft draft = ScanDraft.builder()
                .id(57L).sessionId(SESSION_ID).status(ScanDraftStatus.SKIPPED).matchKind(MatchKind.ALREADY_OWNED)
                .existingItemId(801L).title("Already in this collection").build();
        when(draftRepository.findByIdAndSessionId(57L, SESSION_ID)).thenReturn(Optional.of(draft));

        CollectionEntryResponse existingEntry = CollectionEntryResponse.builder().id(3L).collectionId(COLLECTION_ID).build();
        when(collectionService.findEntryForItem(COLLECTION_ID, 801L, USER_ID)).thenReturn(Optional.of(existingEntry));

        CollectionEntryResponse result = service.approveDraft(SESSION_ID, 57L, USER_ID);

        assertThat(result.getId()).isEqualTo(3L);
        // Must recover the existing entry rather than attempt (and fail) another addEntry call.
        verify(collectionService, never()).addEntry(any(), any(), any());
    }

    @Test
    void discardDraft_deletesStoredPhotosAndTheDraft() {
        stubSessionFound();
        ScanDraft draft = ScanDraft.builder().id(60L).sessionId(SESSION_ID).status(ScanDraftStatus.PENDING).matchKind(MatchKind.MANUAL).build();
        when(draftRepository.findByIdAndSessionId(60L, SESSION_ID)).thenReturn(Optional.of(draft));
        when(photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(60L)).thenReturn(List.of(
                ScanDraftPhoto.builder().id(1L).draftId(60L).storageKey("key-1.jpg").angle("REFERENCE").build()));

        service.discardDraft(SESSION_ID, 60L, USER_ID);

        verify(storageService).delete("key-1.jpg");
        verify(draftRepository).delete(draft);
    }

    @Test
    void mergeDrafts_movesSecondaryPhotosOntoPrimaryAndDeletesSecondary() {
        stubSessionFound();
        ScanDraft primary = ScanDraft.builder().id(70L).sessionId(SESSION_ID).status(ScanDraftStatus.PENDING).matchKind(MatchKind.CONFIDENT).title("Primary").build();
        ScanDraft secondary = ScanDraft.builder().id(71L).sessionId(SESSION_ID).status(ScanDraftStatus.PENDING).matchKind(MatchKind.UNMATCHED).title("Secondary").build();
        when(draftRepository.findByIdAndSessionId(70L, SESSION_ID)).thenReturn(Optional.of(primary));
        when(draftRepository.findByIdAndSessionId(71L, SESSION_ID)).thenReturn(Optional.of(secondary));

        ScanDraftPhoto secondaryPhoto = ScanDraftPhoto.builder().id(9L).draftId(71L).storageKey("k.jpg").angle("FRONT").build();
        when(photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(70L)).thenReturn(List.of()); // primary's own photos, before repointing
        when(photoRepository.findByDraftIdOrderBySortOrderAscIdAsc(71L)).thenReturn(List.of(secondaryPhoto));

        ScanDraftMergeRequest req = new ScanDraftMergeRequest();
        req.setPrimaryDraftId(70L);
        req.setSecondaryDraftId(71L);

        ScanDraftResponse result = service.mergeDrafts(SESSION_ID, req, USER_ID);

        assertThat(result.getTitle()).isEqualTo("Primary");
        assertThat(result.getPhotos()).hasSize(1);
        assertThat(secondaryPhoto.getDraftId()).isEqualTo(70L);
        verify(photoRepository).saveAll(List.of(secondaryPhoto));
        verify(draftRepository).delete(secondary);
    }

    @Test
    void mergeDrafts_rejectsSelfMerge() {
        stubSessionFound();

        ScanDraftMergeRequest req = new ScanDraftMergeRequest();
        req.setPrimaryDraftId(70L);
        req.setSecondaryDraftId(70L);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.bocollections.backend.exception.ConflictException.class,
                () -> service.mergeDrafts(SESSION_ID, req, USER_ID));
        verifyNoInteractions(draftRepository); // never even looks the drafts up
    }

    @Test
    void findSessionOrThrow_wrongUser_throwsNotFound() {
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.empty());

        ScanSessionRequest req = new ScanSessionRequest();
        req.setCollectionId(COLLECTION_ID);

        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> service.listDrafts(SESSION_ID, USER_ID));
    }

    @Test
    void createSession_rejectsCollectionNotOwnedByUser() {
        when(collectionRepository.findByIdAndUserId(COLLECTION_ID, USER_ID)).thenReturn(Optional.empty());

        ScanSessionRequest req = new ScanSessionRequest();
        req.setCollectionId(COLLECTION_ID);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.bocollections.backend.exception.ForbiddenException.class,
                () -> service.createSession(req, USER_ID));
    }

    @Test
    void createSession_success() {
        Collection collection = Collection.builder().id(COLLECTION_ID).userId(USER_ID).name("My Books").build();
        when(collectionRepository.findByIdAndUserId(COLLECTION_ID, USER_ID)).thenReturn(Optional.of(collection));
        when(sessionRepository.save(any(ScanSession.class))).thenAnswer(inv -> {
            ScanSession s = inv.getArgument(0);
            s.setId(idSeq.getAndIncrement());
            return s;
        });

        ScanSessionRequest req = new ScanSessionRequest();
        req.setCollectionId(COLLECTION_ID);

        ScanSessionResponse response = service.createSession(req, USER_ID);

        assertThat(response.getCollectionName()).isEqualTo("My Books");
        assertThat(response.getStatus()).isEqualTo(ScanSessionStatus.OPEN);
    }

    @Test
    void assertPhotoAccessible_throwsForbiddenWhenPhotoBelongsToAnotherUser() {
        ScanDraftPhoto photo = ScanDraftPhoto.builder().id(1L).draftId(60L).storageKey("someone-elses.jpg").angle("REFERENCE").build();
        when(photoRepository.findByStorageKey("someone-elses.jpg")).thenReturn(Optional.of(photo));
        ScanDraft draft = ScanDraft.builder().id(60L).sessionId(SESSION_ID).build();
        when(draftRepository.findById(60L)).thenReturn(Optional.of(draft));
        ScanSession otherUsersSession = ScanSession.builder().id(SESSION_ID).userId(999L).collectionId(COLLECTION_ID).build();
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(otherUsersSession));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.bocollections.backend.exception.ForbiddenException.class,
                () -> service.assertPhotoAccessible("someone-elses.jpg", USER_ID));
    }
}
