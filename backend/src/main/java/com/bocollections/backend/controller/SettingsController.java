package com.bocollections.backend.controller;

import com.bocollections.backend.repository.ResolvedBarcodeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Self-hosted operator utilities — no admin/role concept exists in this app (see User entity),
 * so these sit under the same plain "any authenticated user" access as everything else.
 */
@RestController
@RequestMapping("/settings")
@Tag(name = "Settings", description = "Scanner cache clearing and backend log access")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final ResolvedBarcodeRepository resolvedBarcodeRepository;

    @Value("${logging.file.name}")
    private String logFilePath;

    @DeleteMapping("/scanner-cache")
    @Operation(summary = "Clear the barcode lookup cache",
               description = "Deletes every cached barcode resolution (both real matches and confirmed misses) " +
                             "so the next scan of any barcode re-hits the external lookup chain from scratch. " +
                             "Doesn't touch catalogue items or collections — only the cache.")
    public ResponseEntity<Map<String, Long>> clearScannerCache() {
        long count = resolvedBarcodeRepository.count();
        resolvedBarcodeRepository.deleteAllInBatch();
        log.info("Scanner cache cleared: {} entries removed", count);
        return ResponseEntity.ok(Map.of("cleared", count));
    }

    @GetMapping("/logs/tail")
    @Operation(summary = "Tail the backend log",
               description = "Returns the last N lines (default 200, max 2000) of the current log file as plain text.")
    public ResponseEntity<String> tailLogs(@RequestParam(defaultValue = "200") int lines) {
        int capped = Math.min(Math.max(lines, 1), 2000);
        Path path = Path.of(logFilePath);
        if (!Files.exists(path)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("(no log file yet)");
        }
        // A bounded deque reads the whole file but never holds more than `capped` lines in memory
        // at once — simple and correct at this app's scale (rolling cap keeps the file <= 200MB).
        Deque<String> tail = new ArrayDeque<>(capped);
        try (var stream = Files.lines(path)) {
            stream.forEach(line -> {
                if (tail.size() == capped) tail.removeFirst();
                tail.addLast(line);
            });
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to read log file: " + e.getMessage());
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(String.join("\n", tail));
    }

    @GetMapping("/logs/download")
    @Operation(summary = "Download the full current backend log file")
    public ResponseEntity<FileSystemResource> downloadLogs() {
        Path path = Path.of(logFilePath);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String filename = "bocollections-backend.log";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(new FileSystemResource(path));
    }
}
