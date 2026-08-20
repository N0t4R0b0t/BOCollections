package com.bocollections.backend.controller;

import com.bocollections.backend.dto.*;
import com.bocollections.backend.service.ScanSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/scan-sessions")
@Tag(name = "Scan sessions", description = "Bulk scan mode — session/draft management for the continuous camera capture flow")
@RequiredArgsConstructor
public class ScanSessionController {

    private final ScanSessionService scanSessionService;

    private Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    @PostMapping
    @Operation(summary = "Start a new scan session for a collection")
    public ResponseEntity<ScanSessionResponse> create(@Valid @RequestBody ScanSessionRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scanSessionService.createSession(req, userId(auth)));
    }

    @GetMapping
    @Operation(summary = "List the current user's scan sessions")
    public ResponseEntity<List<ScanSessionResponse>> list(Authentication auth) {
        return ResponseEntity.ok(scanSessionService.listSessions(userId(auth)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single scan session")
    public ResponseEntity<ScanSessionResponse> get(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.getSession(id, userId(auth)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Close or reopen a session", description = "Purely organizational — does not require every draft to be resolved first.")
    public ResponseEntity<ScanSessionResponse> updateStatus(
            @PathVariable Long id, @Valid @RequestBody ScanSessionStatusRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.updateSessionStatus(id, req, userId(auth)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Discard a session", description = "Deletes the session and all its drafts/photos. Destructive — unlike close.")
    public ResponseEntity<Void> discard(@PathVariable Long id, Authentication auth) {
        scanSessionService.discardSession(id, userId(auth));
        return ResponseEntity.noContent().build();
    }

    // --- Drafts ---

    @GetMapping("/{id}/drafts")
    @Operation(summary = "List drafts in a session")
    public ResponseEntity<List<ScanDraftResponse>> listDrafts(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.listDrafts(id, userId(auth)));
    }

    @PostMapping("/{id}/drafts")
    @Operation(summary = "Create a draft",
               description = "Called automatically as the continuous capture loop finalizes each item — " +
                             "never requires a confirmation tap for confident matches.")
    public ResponseEntity<ScanDraftResponse> createDraft(
            @PathVariable Long id, @Valid @RequestBody ScanDraftRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scanSessionService.createDraft(id, req, userId(auth)));
    }

    @PutMapping("/{id}/drafts/{draftId}")
    @Operation(summary = "Edit a draft's fields before approval")
    public ResponseEntity<ScanDraftResponse> updateDraft(
            @PathVariable Long id, @PathVariable Long draftId,
            @Valid @RequestBody ScanDraftUpdateRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.updateDraft(id, draftId, req, userId(auth)));
    }

    @PostMapping("/{id}/drafts/{draftId}/approve")
    @Operation(summary = "Approve a draft", description = "Creates (or reuses) the catalogue item and adds it to the session's collection.")
    public ResponseEntity<CollectionEntryResponse> approveDraft(
            @PathVariable Long id, @PathVariable Long draftId, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scanSessionService.approveDraft(id, draftId, userId(auth)));
    }

    @DeleteMapping("/{id}/drafts/{draftId}")
    @Operation(summary = "Discard a single draft (and its stored photos)")
    public ResponseEntity<Void> discardDraft(@PathVariable Long id, @PathVariable Long draftId, Authentication auth) {
        scanSessionService.discardDraft(id, draftId, userId(auth));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/drafts/{draftId}/photos/{photoId}")
    @Operation(summary = "Remove a single photo from a draft", description = "For dropping a blurry shot or an unwanted fetched reference image without discarding the whole draft.")
    public ResponseEntity<ScanDraftResponse> deletePhoto(
            @PathVariable Long id, @PathVariable Long draftId, @PathVariable Long photoId, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.deletePhoto(id, draftId, photoId, userId(auth)));
    }

    @PostMapping("/{id}/drafts/{draftId}/photos")
    @Operation(summary = "Add more photos to a draft still under review")
    public ResponseEntity<ScanDraftResponse> addPhotos(
            @PathVariable Long id, @PathVariable Long draftId, @Valid @RequestBody ItemPhotosRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.addPhotos(id, draftId, req.getPhotos(), userId(auth)));
    }

    @PatchMapping("/{id}/drafts/{draftId}/photos/{photoId}")
    @Operation(summary = "Change a saved draft photo's angle", description = "The angle picker otherwise only exists pre-upload.")
    public ResponseEntity<ScanDraftResponse> updatePhotoAngle(
            @PathVariable Long id, @PathVariable Long draftId, @PathVariable Long photoId,
            @Valid @RequestBody PhotoAngleUpdateRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.updatePhotoAngle(id, draftId, photoId, req.getAngle(), userId(auth)));
    }

    @PatchMapping("/{id}/drafts/{draftId}/photos/order")
    @Operation(summary = "Reorder a draft's photo gallery", description = "Body is the full list of photo IDs in the desired display order.")
    public ResponseEntity<ScanDraftResponse> reorderPhotos(
            @PathVariable Long id, @PathVariable Long draftId, @Valid @RequestBody PhotoOrderRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.reorderPhotos(id, draftId, req.getPhotoIds(), userId(auth)));
    }

    @PostMapping("/{id}/drafts/{draftId}/reextract")
    @Operation(summary = "Re-run AI vision against a draft's current photos",
               description = "Read-only — returns suggested fields for the caller to review and apply via PUT .../drafts/{draftId}.")
    public ResponseEntity<ExtractResponse> reextractDraft(
            @PathVariable Long id, @PathVariable Long draftId, @RequestBody(required = false) ReextractRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.reextractDraft(id, draftId, req != null ? req.getHint() : null, userId(auth)));
    }

    @PostMapping("/{id}/drafts/merge")
    @Operation(summary = "Merge two drafts", description = "Keeps the primary draft's fields; moves the secondary's photos onto it; deletes the secondary.")
    public ResponseEntity<ScanDraftResponse> mergeDrafts(
            @PathVariable Long id, @Valid @RequestBody ScanDraftMergeRequest req, Authentication auth) {
        return ResponseEntity.ok(scanSessionService.mergeDrafts(id, req, userId(auth)));
    }
}
