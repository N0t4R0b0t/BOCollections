package com.bocollections.backend.controller;

import com.bocollections.backend.dto.ExtractResponse;
import com.bocollections.backend.dto.ReextractRequest;
import com.bocollections.backend.dto.ScanSessionStatusRequest;
import com.bocollections.backend.dto.thrift.*;
import com.bocollections.backend.service.ThriftSessionService;
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
@RequestMapping("/thrift-sessions")
@Tag(name = "Thrift sessions", description = "Persistent thrift-store trips: shelf scanning, held-item identification, and sighting history/search")
@RequiredArgsConstructor
public class ThriftSessionController {

    private final ThriftSessionService thriftSessionService;

    private Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    @PostMapping
    @Operation(summary = "Start a new thrift session")
    public ResponseEntity<ThriftSessionResponse> create(@Valid @RequestBody ThriftSessionRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(thriftSessionService.createSession(req, userId(auth)));
    }

    @GetMapping
    @Operation(summary = "List the current user's thrift sessions")
    public ResponseEntity<List<ThriftSessionResponse>> list(Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.listSessions(userId(auth)));
    }

    @GetMapping("/search")
    @Operation(summary = "Search past sightings by title across all of the user's thrift sessions")
    public ResponseEntity<List<ThriftSightingResponse>> search(@RequestParam String q, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.searchSightings(userId(auth), q));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single thrift session")
    public ResponseEntity<ThriftSessionResponse> get(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.getSession(id, userId(auth)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Close or reopen a session")
    public ResponseEntity<ThriftSessionResponse> updateStatus(
            @PathVariable Long id, @Valid @RequestBody ScanSessionStatusRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.updateSessionStatus(id, req, userId(auth)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Discard a session", description = "Deletes the session and all its sightings/photos.")
    public ResponseEntity<Void> discard(@PathVariable Long id, Authentication auth) {
        thriftSessionService.discardSession(id, userId(auth));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/sightings")
    @Operation(summary = "List sightings recorded in a session")
    public ResponseEntity<List<ThriftSightingResponse>> sightings(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.listSightings(id, userId(auth)));
    }

    @PostMapping("/{id}/scan")
    @Operation(
        summary = "Scan a shelf photo (shelf mode)",
        description = "Sends a photo of a thrift-store shelf to the local Ollama vision model. Returns each " +
                      "identified item with its bounding box and owned status (OWNED / DIFFERENT_VERSION / " +
                      "NOT_OWNED / INTERESTING), and records a sighting per item in this session."
    )
    public ResponseEntity<ThriftScanResponse> scan(
            @PathVariable Long id, @Valid @RequestBody ThriftScanRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.runShelfScan(id, req, userId(auth)));
    }

    @PostMapping("/{id}/shelf/analyze")
    @Operation(
        summary = "Analyze a batch of shelf photos (shelf mode)",
        description = "Shoot-then-analyze: send every shot taken this pass in one request. Each photo is scanned " +
                      "and cross-referenced the same way /scan does; the same title spotted across multiple shots " +
                      "merges into one sighting with a photo-gallery entry (and bbox) per shot it was found in. " +
                      "Returns touched sightings ranked by matchScore (collection relevance) descending."
    )
    public ResponseEntity<List<ThriftSightingResponse>> analyzeShelf(
            @PathVariable Long id, @Valid @RequestBody ThriftShelfAnalyzeRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.analyzeShelf(id, req, userId(auth)));
    }

    @PostMapping("/{id}/classify")
    @Operation(
        summary = "Classify an already-identified item (held-item mode)",
        description = "For an item already identified via /scan/barcode + /scan/verify/extract — decides " +
                      "owned/interesting status and records a sighting, without re-running vision identification."
    )
    public ResponseEntity<ThriftClassifyResponse> classify(
            @PathVariable Long id, @Valid @RequestBody ThriftClassifyRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.classifyHeldItem(id, req, userId(auth)));
    }

    // --- Sighting photo gallery + re-extraction (at-home trip review, not the in-store loop) ---

    @PostMapping("/{id}/sightings/{sightingId}/photos")
    @Operation(summary = "Add photos to a sighting's gallery", description = "For at-home trip review — not part of the fast in-store capture loop.")
    public ResponseEntity<ThriftSightingResponse> addSightingPhotos(
            @PathVariable Long id, @PathVariable Long sightingId, @Valid @RequestBody ThriftSightingPhotosRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.addSightingPhotos(id, sightingId, req.getPhotos(), userId(auth)));
    }

    @DeleteMapping("/{id}/sightings/{sightingId}/photos/{photoId}")
    @Operation(summary = "Remove a single photo from a sighting's gallery")
    public ResponseEntity<ThriftSightingResponse> deleteSightingPhoto(
            @PathVariable Long id, @PathVariable Long sightingId, @PathVariable Long photoId, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.deleteSightingPhoto(id, sightingId, photoId, userId(auth)));
    }

    @PostMapping("/{id}/sightings/{sightingId}/reextract")
    @Operation(summary = "Re-run AI vision against a sighting's photo gallery",
               description = "Read-only — returns suggested fields for the caller to review and apply via PATCH .../sightings/{sightingId}.")
    public ResponseEntity<ExtractResponse> reextractSighting(
            @PathVariable Long id, @PathVariable Long sightingId,
            @RequestBody(required = false) ReextractRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.reextractSighting(id, sightingId, req != null ? req.getHint() : null, userId(auth)));
    }

    @PatchMapping("/{id}/sightings/{sightingId}")
    @Operation(summary = "Edit a sighting's fields", description = "Applies a manual correction or an accepted reextract() suggestion. Only non-null fields are changed.")
    public ResponseEntity<ThriftSightingResponse> updateSighting(
            @PathVariable Long id, @PathVariable Long sightingId, @Valid @RequestBody ThriftSightingUpdateRequest req, Authentication auth) {
        return ResponseEntity.ok(thriftSessionService.updateSighting(id, sightingId, req, userId(auth)));
    }
}
