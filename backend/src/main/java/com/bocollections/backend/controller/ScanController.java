package com.bocollections.backend.controller;

import com.bocollections.backend.dto.*;
import com.bocollections.backend.service.VisualScanService;
import com.bocollections.backend.service.lookup.MetadataLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/scan")
@Tag(name = "Scan", description = "Barcode lookup and AI-assisted item identification")
@RequiredArgsConstructor
public class ScanController {

    private final MetadataLookupService metadataLookupService;
    private final VisualScanService visualScanService;

    private Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    @GetMapping("/barcode/{barcode}")
    @Operation(
        summary = "Look up a barcode",
        description = "Checks the local catalogue first (fastest). Falls back to Open Library for ISBNs, " +
                      "then Discogs and MusicBrainz for UPC barcodes. Returns `source=NOT_FOUND` when " +
                      "no match is found anywhere. Always includes `ownedInCollections` for the current user. " +
                      "Pass `exclude` (repeatable, e.g. `?exclude=DISCOGS&exclude=MUSICBRAINZ`) to skip " +
                      "source(s) the caller already rejected as a wrong match and continue the waterfall. " +
                      "Pass `excludeExternalId` (repeatable) to reject a specific TMDB match and try the " +
                      "next-best candidate from the same title search, without giving up on TMDB entirely.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lookup result (may have source=NOT_FOUND)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
        }
    )
    public ResponseEntity<LookupResult> lookupBarcode(
            @Parameter(description = "EAN-13, UPC-A, ISBN-13, or ISBN-10", example = "9780743273565")
            @PathVariable String barcode,
            @Parameter(description = "Source(s) to skip, e.g. after the user rejected that source's match")
            @RequestParam(required = false) Set<String> exclude,
            @Parameter(description = "TMDB id(s) to skip, e.g. after the user rejected that specific movie/show")
            @RequestParam(required = false) Set<String> excludeExternalId,
            Authentication auth) {
        return ResponseEntity.ok(metadataLookupService.lookup(barcode, userId(auth),
                exclude == null ? Set.of() : exclude,
                excludeExternalId == null ? Set.of() : excludeExternalId));
    }

    @PostMapping("/verify")
    @Operation(
        summary = "Visual confirmation",
        description = "Sends a camera frame (base64 JPEG) to the local Ollama vision model and asks " +
                      "whether it matches the supplied lookup result. Responds quickly even on degraded " +
                      "confidence — `matches: null` means the vision model was unavailable.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Verification result"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
        }
    )
    public ResponseEntity<ScanVerifyResponse> verify(
            @Valid @RequestBody ScanVerifyRequest req,
            Authentication auth) {
        return ResponseEntity.ok(visualScanService.verify(req));
    }

    @PostMapping("/extract")
    @Operation(
        summary = "Extract metadata from images",
        description = "Sends one or more base64 JPEG captures to the Ollama vision model and returns " +
                      "structured item metadata. Designed for guided capture (front/back/spine). " +
                      "The optional `hint` field ('this is a vinyl record') improves accuracy for ambiguous items.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Extracted metadata with confidence level"),
            @ApiResponse(responseCode = "400", description = "No images provided")
        }
    )
    public ResponseEntity<ExtractResponse> extract(
            @Valid @RequestBody ExtractRequest req,
            Authentication auth) {
        return ResponseEntity.ok(visualScanService.extract(req));
    }
}
