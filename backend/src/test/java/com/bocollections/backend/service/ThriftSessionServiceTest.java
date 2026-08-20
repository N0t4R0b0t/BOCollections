package com.bocollections.backend.service;

import com.bocollections.backend.dto.thrift.*;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.OwnedStatus;
import com.bocollections.backend.entity.ThriftSession;
import com.bocollections.backend.entity.ThriftSighting;
import com.bocollections.backend.entity.ThriftSightingPhoto;
import com.bocollections.backend.entity.ThriftSourceMode;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.ThriftSessionRepository;
import com.bocollections.backend.repository.ThriftSightingPhotoRepository;
import com.bocollections.backend.repository.ThriftSightingRepository;
import com.bocollections.backend.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThriftSessionServiceTest {

    @Mock private ThriftSessionRepository sessionRepository;
    @Mock private ThriftSightingRepository sightingRepository;
    @Mock private ThriftSightingPhotoRepository photoRepository;
    @Mock private ThriftService thriftService;
    @Mock private VisualScanService visualScanService;
    @Mock private TasteProfileService tasteProfileService;
    @Mock private StorageService storageService;
    @Mock private PlatformTransactionManager transactionManager;

    private ThriftSessionService service;

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;

    @BeforeEach
    void setUp() {
        service = new ThriftSessionService(sessionRepository, sightingRepository, photoRepository, thriftService, visualScanService, tasteProfileService, storageService, transactionManager);
        lenient().when(thriftService.normalizeTitle(any())).thenAnswer(inv -> ((String) inv.getArgument(0)).toLowerCase());
        lenient().when(sightingRepository.save(any(ThriftSighting.class))).thenAnswer(inv -> inv.getArgument(0));
        // New sightings are inserted via a TransactionTemplate-wrapped saveAndFlush (see
        // ThriftSessionService.tryInsert) so a lost unique-constraint race rolls back only that
        // savepoint, not the whole request — this mocked manager just needs to let it run for real.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().when(sightingRepository.saveAndFlush(any(ThriftSighting.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubSessionFound() {
        ThriftSession session = ThriftSession.builder().id(SESSION_ID).userId(USER_ID).build();
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
    }

    @Test
    void runShelfScan_recordsOneSightingPerIdentifiedItem() {
        stubSessionFound();
        ThriftScanRequest req = new ThriftScanRequest();
        req.setImageBase64(Base64.getEncoder().encodeToString("fake".getBytes()));

        ThriftItem itemA = ThriftItem.builder().title("Item A").category(MediaCategory.AUDIO).format("CD").ownedStatus(OwnedStatus.NOT_OWNED).build();
        ThriftItem itemB = ThriftItem.builder().title("Item B").category(MediaCategory.PRINT).format("Book").ownedStatus(OwnedStatus.OWNED).itemId(5L).build();
        when(thriftService.scan(req, USER_ID)).thenReturn(ThriftScanResponse.builder().items(List.of(itemA, itemB)).build());
        when(storageService.store(any(), any())).thenReturn("shelf-photo.jpg");
        when(sightingRepository.findBySessionIdAndNormalizedTitle(eq(SESSION_ID), any())).thenReturn(Optional.empty());

        service.runShelfScan(SESSION_ID, req, USER_ID);

        ArgumentCaptor<ThriftSighting> captor = ArgumentCaptor.forClass(ThriftSighting.class);
        verify(sightingRepository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).extracting(ThriftSighting::getTitle).containsExactlyInAnyOrder("Item A", "Item B");
        assertThat(captor.getAllValues()).allMatch(s -> ThriftSourceMode.SHELF.equals(s.getSourceMode()));

        ArgumentCaptor<ThriftSightingPhoto> photoCaptor = ArgumentCaptor.forClass(ThriftSightingPhoto.class);
        verify(photoRepository, times(2)).save(photoCaptor.capture());
        assertThat(photoCaptor.getAllValues()).allMatch(p -> "shelf-photo.jpg".equals(p.getStorageKey()));
    }

    @Test
    void recordSighting_updatesClassificationOnSameSessionCollision_notJustTimesSeen() {
        stubSessionFound();
        ThriftClassifyRequest req = new ThriftClassifyRequest();
        req.setTitle("Repeat Item");
        req.setCategory(MediaCategory.AUDIO);
        req.setFormat("CD");
        req.setExistingItemId(500L);
        req.setOwnedInCollections(List.of(1L));
        req.setCollectionIds(List.of(1L));

        ThriftSighting existing = ThriftSighting.builder()
                .id(99L).sessionId(SESSION_ID).userId(USER_ID).title("Repeat Item").normalizedTitle("repeat item")
                .ownedStatus(OwnedStatus.NOT_OWNED).itemId(null).timesSeen(1).build();
        when(sightingRepository.findBySessionIdAndNormalizedTitle(SESSION_ID, "repeat item")).thenReturn(Optional.of(existing));
        // Simulates a later, more authoritative barcode-confirmed classification superseding an
        // earlier fuzzy-matched NOT_OWNED sighting of the same title in this session.
        when(thriftService.classifyItem(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ThriftService.ClassificationResult(OwnedStatus.OWNED, 500L));

        service.classifyHeldItem(SESSION_ID, req, USER_ID);

        ArgumentCaptor<ThriftSighting> captor = ArgumentCaptor.forClass(ThriftSighting.class);
        verify(sightingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L); // same row, not a new one
        assertThat(captor.getValue().getTimesSeen()).isEqualTo(2);
        assertThat(captor.getValue().getOwnedStatus()).isEqualTo(OwnedStatus.OWNED);
        assertThat(captor.getValue().getItemId()).isEqualTo(500L);
    }

    @Test
    void recordSighting_appendsPhotoOnRepeatSighting_doesNotDeleteEarlierOnes() {
        stubSessionFound();
        ThriftClassifyRequest req = new ThriftClassifyRequest();
        req.setTitle("Repeat Item");
        req.setImageBase64(Base64.getEncoder().encodeToString("fake".getBytes()));

        ThriftSighting existing = ThriftSighting.builder()
                .id(99L).sessionId(SESSION_ID).userId(USER_ID).title("Repeat Item").normalizedTitle("repeat item")
                .ownedStatus(OwnedStatus.NOT_OWNED).timesSeen(1).build();
        when(sightingRepository.findBySessionIdAndNormalizedTitle(SESSION_ID, "repeat item")).thenReturn(Optional.of(existing));
        when(thriftService.classifyItem(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ThriftService.ClassificationResult(OwnedStatus.NOT_OWNED, null));
        when(storageService.store(any(), any())).thenReturn("second-photo.jpg");

        service.classifyHeldItem(SESSION_ID, req, USER_ID);

        verify(storageService, never()).delete(any()); // every capture is kept, nothing discarded
        ArgumentCaptor<ThriftSightingPhoto> photoCaptor = ArgumentCaptor.forClass(ThriftSightingPhoto.class);
        verify(photoRepository).save(photoCaptor.capture());
        assertThat(photoCaptor.getValue().getStorageKey()).isEqualTo("second-photo.jpg");
        assertThat(photoCaptor.getValue().getSightingId()).isEqualTo(99L);
    }

    @Test
    void runShelfScan_doesNotStorePhotoWhenNoItemsIdentified() {
        stubSessionFound();
        ThriftScanRequest req = new ThriftScanRequest();
        req.setImageBase64(Base64.getEncoder().encodeToString("fake".getBytes()));
        when(thriftService.scan(req, USER_ID)).thenReturn(ThriftScanResponse.builder().items(List.of()).build());

        service.runShelfScan(SESSION_ID, req, USER_ID);

        verifyNoInteractions(storageService); // nothing identified -> nothing to attach a photo to
    }

    @Test
    void recordSighting_insertConflict_fallsBackToUpdatingWinnerRow() {
        stubSessionFound();
        ThriftScanRequest req = new ThriftScanRequest();
        req.setImageBase64(Base64.getEncoder().encodeToString("fake".getBytes()));
        ThriftItem item = ThriftItem.builder().title("Raced Item").category(MediaCategory.AUDIO).format("CD").ownedStatus(OwnedStatus.NOT_OWNED).build();
        when(thriftService.scan(req, USER_ID)).thenReturn(ThriftScanResponse.builder().items(List.of(item)).build());
        when(storageService.store(any(), any())).thenReturn("shelf-photo.jpg");

        // First check (before the attempted insert) finds nothing; a concurrent request wins the
        // race and the second check (inside the conflict fallback) finds that winner's row.
        ThriftSighting winner = ThriftSighting.builder()
                .id(77L).sessionId(SESSION_ID).userId(USER_ID).title("Raced Item").normalizedTitle("raced item")
                .ownedStatus(OwnedStatus.NOT_OWNED).timesSeen(1).build();
        when(sightingRepository.findBySessionIdAndNormalizedTitle(SESSION_ID, "raced item"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(sightingRepository.saveAndFlush(any(ThriftSighting.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        service.runShelfScan(SESSION_ID, req, USER_ID);

        ArgumentCaptor<ThriftSighting> captor = ArgumentCaptor.forClass(ThriftSighting.class);
        verify(sightingRepository).save(captor.capture()); // fell back to updating the winner, not a second insert
        assertThat(captor.getValue().getId()).isEqualTo(77L);
        assertThat(captor.getValue().getTimesSeen()).isEqualTo(2);
    }

    @Test
    void recordSighting_doesNotDowngradeAnOwnedSightingToNotOwned() {
        stubSessionFound();
        ThriftClassifyRequest req = new ThriftClassifyRequest();
        req.setTitle("Repeat Item");

        ThriftSighting existing = ThriftSighting.builder()
                .id(99L).sessionId(SESSION_ID).userId(USER_ID).title("Repeat Item").normalizedTitle("repeat item")
                .ownedStatus(OwnedStatus.OWNED).itemId(500L).timesSeen(1).build();
        when(sightingRepository.findBySessionIdAndNormalizedTitle(SESSION_ID, "repeat item")).thenReturn(Optional.of(existing));
        // A later, weaker pass (e.g. a vague re-glance) must not silently erase the earlier
        // barcode-confirmed OWNED result.
        when(thriftService.classifyItem(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ThriftService.ClassificationResult(OwnedStatus.NOT_OWNED, null));

        service.classifyHeldItem(SESSION_ID, req, USER_ID);

        ArgumentCaptor<ThriftSighting> captor = ArgumentCaptor.forClass(ThriftSighting.class);
        verify(sightingRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnedStatus()).isEqualTo(OwnedStatus.OWNED); // unchanged
        assertThat(captor.getValue().getItemId()).isEqualTo(500L); // unchanged
        assertThat(captor.getValue().getTimesSeen()).isEqualTo(2); // still bumped
    }

    @Test
    void recordSighting_interestingFlagSurvivesALaterPlainNotOwnedResult() {
        stubSessionFound();
        ThriftClassifyRequest req = new ThriftClassifyRequest();
        req.setTitle("Repeat Item");

        ThriftSighting existing = ThriftSighting.builder()
                .id(99L).sessionId(SESSION_ID).userId(USER_ID).title("Repeat Item").normalizedTitle("repeat item")
                .ownedStatus(OwnedStatus.INTERESTING).timesSeen(1).build();
        when(sightingRepository.findBySessionIdAndNormalizedTitle(SESSION_ID, "repeat item")).thenReturn(Optional.of(existing));
        when(thriftService.classifyItem(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ThriftService.ClassificationResult(OwnedStatus.NOT_OWNED, null));

        service.classifyHeldItem(SESSION_ID, req, USER_ID);

        ArgumentCaptor<ThriftSighting> captor = ArgumentCaptor.forClass(ThriftSighting.class);
        verify(sightingRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnedStatus()).isEqualTo(OwnedStatus.INTERESTING); // sticky, not cleared
    }

    @Test
    void listSightings_wrongUser_throwsNotFound() {
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> service.listSightings(SESSION_ID, USER_ID));
    }
}
