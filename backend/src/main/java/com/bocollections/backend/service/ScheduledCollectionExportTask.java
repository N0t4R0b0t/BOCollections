package com.bocollections.backend.service;

import com.bocollections.backend.config.ScheduledExportProperties;
import com.bocollections.backend.dto.export.CollectionExport;
import com.bocollections.backend.dto.export.ExportEntry;
import com.bocollections.backend.entity.Collection;
import com.bocollections.backend.repository.CollectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Background JSON backup — every collection (all users, since this runs server-wide on a
 * schedule, not on behalf of one request) gets its own file in app.export.scheduled.directory,
 * same self-contained shape (photos embedded as base64) as GET /collections/{id}/export/json.
 * Off by default (app.export.scheduled.enabled) — see application.yml for the env vars. Each
 * file is overwritten in place every run (named by user+collection id, not by run timestamp) —
 * a rolling snapshot, not an ever-growing history that would need its own retention/cleanup.
 * Skips the write entirely when a collection's export would be identical to what's already on
 * disk (see hasChanged) — so a file's mtime is meaningful (last actual change, not last run) and
 * an idle instance doesn't churn disk I/O every interval for no reason. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledCollectionExportTask {

    private final ScheduledExportProperties properties;
    private final CollectionRepository collectionRepository;
    private final CollectionExportService collectionExportService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedRateString = "${app.export.scheduled.interval-ms:86400000}")
    public void exportAll() {
        if (!properties.isEnabled()) return;

        Path dir = Path.of(properties.getDirectory());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Scheduled collection export: could not create directory {}: {}", dir, e.getMessage());
            return;
        }

        List<Collection> collections = collectionRepository.findAll();
        int succeeded = 0;
        int unchanged = 0;
        int failed = 0;
        for (Collection collection : collections) {
            try {
                CollectionExport data = collectionExportService.exportJson(collection.getId(), collection.getUserId());
                Path file = dir.resolve(fileNameFor(collection));
                if (!hasChanged(data, file)) {
                    unchanged++;
                    continue;
                }
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
                succeeded++;
            } catch (Exception e) {
                failed++;
                log.warn("Scheduled export failed for collection {}: {}", collection.getId(), e.getMessage());
            }
        }
        log.info("Scheduled collection export: {} written, {} unchanged, {} failed, directory={}",
                succeeded, unchanged, failed, dir.toAbsolutePath());
    }

    /** True if this collection's export differs from what's already on disk for it — false (skip
     * the write) if nothing meaningful changed. Two things vary run-to-run even when nothing
     * about the collection actually did, so both are normalized out before comparing: `exportedAt`
     * (always "now"), and entry order (the underlying query has no explicit ORDER BY, so isn't
     * guaranteed stable across runs). No file on disk yet always counts as changed. */
    private boolean hasChanged(CollectionExport data, Path file) {
        if (!Files.exists(file)) return true;
        try {
            CollectionExport existing = objectMapper.readValue(file.toFile(), CollectionExport.class);
            return !comparable(data).equals(comparable(existing));
        } catch (Exception e) {
            log.warn("Could not compare against existing export {}, treating as changed: {}", file, e.getMessage());
            return true;
        }
    }

    private static CollectionExport comparable(CollectionExport data) {
        List<ExportEntry> sortedEntries = data.getEntries() == null ? List.of() : data.getEntries().stream()
                .sorted(Comparator.comparing(e -> e.getItem() != null && e.getItem().getTitle() != null ? e.getItem().getTitle() : ""))
                .toList();
        return CollectionExport.builder()
                .collectionName(data.getCollectionName())
                .description(data.getDescription())
                .primaryCategory(data.getPrimaryCategory())
                .exportedAt(null)
                .entries(sortedEntries)
                .build();
    }

    private static String fileNameFor(Collection collection) {
        String name = collection.getName();
        String slug = (name == null || name.isBlank() ? "collection" : name)
                .replaceAll("[^a-zA-Z0-9-]+", "-").replaceAll("^-+|-+$", "").toLowerCase();
        return collection.getUserId() + "-" + collection.getId() + "-" + (slug.isBlank() ? "collection" : slug) + ".json";
    }
}
