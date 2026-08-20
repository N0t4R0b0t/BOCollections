package com.bocollections.backend.controller;

import com.bocollections.backend.dto.*;
import com.bocollections.backend.dto.export.CollectionExport;
import com.bocollections.backend.service.CollectionExportService;
import com.bocollections.backend.service.CollectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/collections")
@Tag(name = "Collections", description = "User collections and their entries. Each collection is private to its owner.")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;
    private final CollectionExportService collectionExportService;

    private Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    @GetMapping
    @Operation(summary = "List all collections for the authenticated user")
    public ResponseEntity<List<CollectionResponse>> list(Authentication auth) {
        return ResponseEntity.ok(collectionService.getCollections(userId(auth)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single collection")
    public ResponseEntity<CollectionResponse> get(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(collectionService.getCollection(id, userId(auth)));
    }

    @PostMapping
    @Operation(summary = "Create a new collection")
    public ResponseEntity<CollectionResponse> create(@Valid @RequestBody CollectionRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(collectionService.create(req, userId(auth)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a collection")
    public ResponseEntity<CollectionResponse> update(
            @PathVariable Long id, @Valid @RequestBody CollectionRequest req, Authentication auth) {
        return ResponseEntity.ok(collectionService.update(id, req, userId(auth)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a collection")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        collectionService.delete(id, userId(auth));
        return ResponseEntity.noContent().build();
    }

    // --- Entries ---

    @GetMapping("/{id}/entries")
    @Operation(summary = "List (optionally search) entries in a collection",
               description = "Items are batch-loaded in two queries regardless of page size (no N+1). " +
                             "Optional `q` filters by item title/publisher, scoped to this collection.")
    public ResponseEntity<Page<CollectionEntryResponse>> entries(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String q,
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(collectionService.getEntries(id, userId(auth), q, pageable));
    }

    @PostMapping("/{id}/entries")
    @Operation(summary = "Add an item to a collection",
               description = "itemId must already exist in the catalogue. Returns 409 if the item is already in this collection.")
    public ResponseEntity<CollectionEntryResponse> addEntry(
            @PathVariable Long id,
            @Valid @RequestBody CollectionEntryRequest req,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(collectionService.addEntry(id, req, userId(auth)));
    }

    @PutMapping("/{id}/entries/{entryId}")
    @Operation(summary = "Update a collection entry (condition, notes, etc.)")
    public ResponseEntity<CollectionEntryResponse> updateEntry(
            @PathVariable Long id,
            @PathVariable Long entryId,
            @Valid @RequestBody CollectionEntryRequest req,
            Authentication auth) {
        return ResponseEntity.ok(collectionService.updateEntry(id, entryId, req, userId(auth)));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    @Operation(summary = "Remove an item from a collection")
    public ResponseEntity<Void> removeEntry(
            @PathVariable Long id,
            @PathVariable Long entryId,
            Authentication auth) {
        collectionService.removeEntry(id, entryId, userId(auth));
        return ResponseEntity.noContent().build();
    }

    // --- Export / Import ---

    @GetMapping("/{id}/export/excel")
    @Operation(summary = "Export a collection as an Excel spreadsheet",
               description = "One row per entry — title, format, condition, location, etc. Read-only; there is no import path back from this file (see the JSON export/import for full-fidelity backup/restore/transfer).")
    public ResponseEntity<byte[]> exportExcel(@PathVariable Long id, Authentication auth) {
        byte[] bytes = collectionExportService.exportExcel(id, userId(auth));
        String filename = attachmentFilename(id, auth, "xlsx");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(bytes);
    }

    @GetMapping("/{id}/export/json")
    @Operation(summary = "Export a collection as a self-contained JSON file",
               description = "Every item field plus its full photo gallery, photos embedded as base64 (not storage-key references) — the file survives being moved to a different BOCollections instance and re-imported via POST /{id}/import/json.")
    public ResponseEntity<CollectionExport> exportJson(@PathVariable Long id, Authentication auth) {
        CollectionExport data = collectionExportService.exportJson(id, userId(auth));
        String filename = attachmentFilename(id, auth, "json");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(data);
    }

    @PostMapping("/{id}/import/json")
    @Operation(summary = "Import items from a previously-exported JSON file into this collection",
               description = "Always creates new items (no dedup against the catalogue) — for restoring a backup or bringing in someone else's exported collection, not merging into an existing one. Best-effort: a bad entry is skipped rather than aborting the whole import.")
    public ResponseEntity<Map<String, Integer>> importJson(
            @PathVariable Long id, @RequestBody CollectionExport data, Authentication auth) {
        int imported = collectionExportService.importJson(id, userId(auth), data);
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    private String attachmentFilename(Long collectionId, Authentication auth, String extension) {
        String name = collectionService.getCollection(collectionId, userId(auth)).getName();
        String slug = (name == null || name.isBlank() ? "collection" : name)
                .replaceAll("[^a-zA-Z0-9-]+", "-").replaceAll("^-+|-+$", "").toLowerCase();
        return (slug.isBlank() ? "collection" : slug) + "-" + LocalDate.now() + "." + extension;
    }
}
