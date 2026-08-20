package com.bocollections.backend.controller;

import com.bocollections.backend.dto.ExtractResponse;
import com.bocollections.backend.dto.ItemFacetsResponse;
import com.bocollections.backend.dto.ItemPhotosRequest;
import com.bocollections.backend.dto.ItemRequest;
import com.bocollections.backend.dto.ItemResponse;
import com.bocollections.backend.dto.ItemSearchCriteria;
import com.bocollections.backend.dto.ItemUpdateRequest;
import com.bocollections.backend.dto.PhotoAngleUpdateRequest;
import com.bocollections.backend.dto.PhotoOrderRequest;
import com.bocollections.backend.dto.ReextractRequest;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.service.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/items")
@Tag(name = "Items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @GetMapping
    @Operation(summary = "Search/filter items", description = "q alone behaves exactly as before; category/format/yearFrom/yearTo/genre/sort are additional optional filters for the catalogue's filter+sort builder.")
    public ResponseEntity<Page<ItemResponse>> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) MediaCategory category,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) Integer yearTo,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String sort,
            @PageableDefault(size = 20) Pageable pageable) {
        boolean hasFilters = category != null || format != null || yearFrom != null || yearTo != null || genre != null || sort != null;
        if (!hasFilters) {
            return ResponseEntity.ok(itemService.search(q, pageable));
        }
        return ResponseEntity.ok(itemService.search(new ItemSearchCriteria(q, category, format, yearFrom, yearTo, genre, sort), pageable));
    }

    @GetMapping("/facets")
    @Operation(summary = "Year range and genre list available in the catalogue", description = "Optionally scoped to a category — bounds for the filter builder's year slider and its genre dropdown, so neither ever offers a value that returns zero results.")
    public ResponseEntity<ItemFacetsResponse> facets(@RequestParam(required = false) MediaCategory category) {
        return ResponseEntity.ok(itemService.getFacets(category));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get item by ID")
    public ResponseEntity<ItemResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(itemService.getById(id));
    }

    @GetMapping("/barcode/{barcode}")
    @Operation(summary = "Look up item by barcode / ISBN")
    public ResponseEntity<ItemResponse> getByBarcode(@PathVariable String barcode) {
        return ResponseEntity.ok(itemService.getByBarcode(barcode));
    }

    @PostMapping
    @Operation(summary = "Create a new item")
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(itemService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an item")
    public ResponseEntity<ItemResponse> update(@PathVariable Long id, @Valid @RequestBody ItemRequest req) {
        return ResponseEntity.ok(itemService.update(id, req));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update an item", description = "Only touches fields present in the request body — unlike PUT, safe for callers that only want to change one or two fields (e.g. picking a cover photo, applying an AI re-extraction suggestion) without risking nulling out the rest.")
    public ResponseEntity<ItemResponse> patch(@PathVariable Long id, @RequestBody ItemUpdateRequest req) {
        return ResponseEntity.ok(itemService.patch(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an item")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        itemService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Photo gallery ---

    @PostMapping("/{id}/photos")
    @Operation(summary = "Add photos to an item's gallery", description = "For revisiting an already-owned item to add more shots, e.g. before re-running AI vision.")
    public ResponseEntity<ItemResponse> addPhotos(@PathVariable Long id, @Valid @RequestBody ItemPhotosRequest req) {
        return ResponseEntity.ok(itemService.addPhotos(id, req.getPhotos()));
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    @Operation(summary = "Remove a single photo from an item's gallery")
    public ResponseEntity<ItemResponse> deletePhoto(@PathVariable Long id, @PathVariable Long photoId) {
        return ResponseEntity.ok(itemService.deletePhoto(id, photoId));
    }

    @PatchMapping("/{id}/photos/{photoId}")
    @Operation(summary = "Change a saved photo's angle", description = "The angle picker otherwise only exists pre-upload.")
    public ResponseEntity<ItemResponse> updatePhotoAngle(@PathVariable Long id, @PathVariable Long photoId, @Valid @RequestBody PhotoAngleUpdateRequest req) {
        return ResponseEntity.ok(itemService.updatePhotoAngle(id, photoId, req.getAngle()));
    }

    @PatchMapping("/{id}/photos/order")
    @Operation(summary = "Reorder an item's photo gallery", description = "Body is the full list of photo IDs in the desired display order.")
    public ResponseEntity<ItemResponse> reorderPhotos(@PathVariable Long id, @Valid @RequestBody PhotoOrderRequest req) {
        return ResponseEntity.ok(itemService.reorderPhotos(id, req.getPhotoIds()));
    }

    @PostMapping("/{id}/reextract")
    @Operation(summary = "Re-run AI vision against an item's photo gallery",
               description = "Read-only — returns suggested fields for the caller to review and apply via PUT /items/{id}, same as the capture flow's own draft-then-approve pattern.")
    public ResponseEntity<ExtractResponse> reextract(@PathVariable Long id, @RequestBody(required = false) ReextractRequest req) {
        return ResponseEntity.ok(itemService.reextract(id, req != null ? req.getHint() : null));
    }
}
