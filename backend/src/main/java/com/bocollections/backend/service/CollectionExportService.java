package com.bocollections.backend.service;

import com.bocollections.backend.dto.CollectionEntryRequest;
import com.bocollections.backend.dto.ItemRequest;
import com.bocollections.backend.dto.ScanDraftPhotoRequest;
import com.bocollections.backend.dto.export.CollectionExport;
import com.bocollections.backend.dto.export.ExportEntry;
import com.bocollections.backend.dto.export.ExportItem;
import com.bocollections.backend.dto.export.ExportPhoto;
import com.bocollections.backend.entity.Collection;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.ItemPhoto;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.exception.NotFoundException;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.CollectionRepository;
import com.bocollections.backend.repository.ItemPhotoRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.bocollections.backend.service.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Collection export (Excel, self-contained JSON with base64-embedded photos) and JSON import.
 * Excel is a flat, human-readable spreadsheet of the collection's entries — read-only, no import
 * path back from it (round-tripping a spreadsheet reliably would need a rigid column contract;
 * JSON already covers the backup/restore/transfer use case). JSON is the full-fidelity format:
 * every item field plus its entire photo gallery, meant to survive a round trip to a totally
 * different BOCollections instance (photos are embedded bytes, not storage-key references). */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionExportService {

    private final CollectionRepository collectionRepository;
    private final CollectionEntryRepository entryRepository;
    private final ItemRepository itemRepository;
    private final ItemPhotoRepository photoRepository;
    private final StorageService storageService;
    private final ItemService itemService;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    // "Cover" (an embedded image, handled separately from the text columns below) is always
    // column 0 — everything else shifts one column to the right of FIXED_HEADERS[i].
    private static final String[] FIXED_HEADERS = {
            "Title", "Subtitle", "Category", "Format", "Publisher", "Release Year",
            "Barcode", "Barcode Type", "Condition", "Location", "Acquisition Date", "Purchase Price", "Notes",
            "Description", "Source",
    };
    private static final int[] FIXED_COLUMN_WIDTHS_CHARS =
            { 32, 22, 12, 16, 22, 12, 16, 12, 12, 16, 16, 12, 32, 40, 12 };
    private static final int METADATA_COLUMN_WIDTH_CHARS = 24;
    private static final int COVER_URL_COLUMN_WIDTH_CHARS = 42;
    private static final int COVER_COLUMN_WIDTH_CHARS = 11;
    private static final float COVER_ROW_HEIGHT_POINTS = 72f; // ~96px at 96dpi — a bit taller than the thumbnail so it isn't flush against the row borders
    private static final int COVER_THUMBNAIL_MAX_PX = 160; // downscaled before embedding — see resolveCoverThumbnail's doc comment

    /** Internal audit trail (rejected AI findings, raw external-API dumps) — rides along inside
     * an approved item's own metadata (see CollectionService/ScanSessionService's approve path),
     * useful for debugging but not something a collector wants cluttering their spreadsheet. */
    private static final String DEBUG_METADATA_KEY = "_debug";

    // Same User-Agent/redirect fix as ScanSessionService.IMAGE_DOWNLOAD_CLIENT — several source
    // CDNs (eBay, retailer listing images, TMDB in some configurations) 403 requests that don't
    // look like a browser, or redirect http->https, both of which a bare default HttpClient trips
    // over silently. A cover's own external URL goes through this same class of request here.
    private static final HttpClient COVER_DOWNLOAD_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Transactional(readOnly = true)
    public byte[] exportExcel(Long collectionId, Long userId) {
        Collection collection = findOwnedOrThrow(collectionId, userId);
        List<CollectionEntry> entries = entryRepository.findByCollectionId(collectionId);
        Map<Long, Item> itemsById = loadItems(entries);
        List<Item> items = entries.stream()
                .map(e -> itemsById.get(e.getItemId()))
                .filter(java.util.Objects::nonNull)
                .toList();

        // One column per metadata key actually used anywhere in this collection, rather than a
        // single flattened "Extra Details" cell — director/cast/genres/runtime/platform/etc. each
        // get their own column so they're independently sortable/filterable. Alphabetical order
        // keeps the column set stable across exports instead of shuffling based on which item
        // happened to come first.
        List<String> metadataKeys = new TreeSet<>(items.stream()
                .flatMap(i -> metadataKeys(i.getMetadata()).stream())
                .collect(Collectors.toSet())).stream().toList();

        List<String> headers = new ArrayList<>(1 + FIXED_HEADERS.length + metadataKeys.size() + 1);
        headers.add("Cover");
        headers.addAll(List.of(FIXED_HEADERS));
        metadataKeys.forEach(k -> headers.add(humanizeKey(k)));
        headers.add("Cover URL");

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(collection.getName()));
            XSSFDrawing drawing = ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).createDrawingPatriarch();

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.INDIGO.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.LEFT);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            Row header = sheet.createRow(0);
            header.setHeightInPoints(20);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            CellStyle bodyStyle = wb.createCellStyle();
            bodyStyle.setBorderBottom(BorderStyle.THIN);
            bodyStyle.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            bodyStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            CellStyle stripedStyle = wb.createCellStyle();
            stripedStyle.cloneStyleFrom(bodyStyle);
            stripedStyle.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
            stripedStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int rowIdx = 1;
            for (CollectionEntry entry : entries) {
                Item item = itemsById.get(entry.getItemId());
                if (item == null) continue;
                Row row = sheet.createRow(rowIdx);
                row.setHeightInPoints(COVER_ROW_HEIGHT_POINTS);
                CellStyle rowStyle = (rowIdx % 2 == 0) ? stripedStyle : bodyStyle;
                row.createCell(0).setCellStyle(rowStyle); // cover cell itself stays empty — the picture floats on top of it
                writeExcelRow(row, entry, item, metadataKeys, rowStyle);
                embedCoverThumbnail(wb, drawing, item, rowIdx);
                rowIdx++;
            }

            sheet.setColumnWidth(0, COVER_COLUMN_WIDTH_CHARS * 256);
            for (int i = 0; i < FIXED_COLUMN_WIDTHS_CHARS.length; i++) {
                sheet.setColumnWidth(1 + i, FIXED_COLUMN_WIDTHS_CHARS[i] * 256);
            }
            for (int i = 0; i < metadataKeys.size(); i++) {
                sheet.setColumnWidth(1 + FIXED_HEADERS.length + i, METADATA_COLUMN_WIDTH_CHARS * 256);
            }
            sheet.setColumnWidth(headers.size() - 1, COVER_URL_COLUMN_WIDTH_CHARS * 256);

            // Filter-bar dropdowns on every header cell, and freeze the header row so it stays
            // visible while scrolling a long collection — the two things actually asked for.
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowIdx - 1, 0), 0, headers.size() - 1));
            sheet.createFreezePane(0, 1);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Best-effort: resolves the item's cover (local storage, external URL, or — if neither is
     * set — its first gallery photo, same fallback ItemService.toResponse uses for list views),
     * downscales it, and floats it over the (deliberately left blank) cover cell. Anything going
     * wrong here — missing file, unreachable CDN, unsupported image format — just leaves that row
     * without a thumbnail rather than failing the whole export. */
    private void embedCoverThumbnail(XSSFWorkbook wb, XSSFDrawing drawing, Item item, int rowIdx) {
        byte[] thumbnail = resolveCoverThumbnail(item);
        if (thumbnail == null) return;
        try {
            int pictureIdx = wb.addPicture(thumbnail, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG);
            // Small inset (~3px on each side, in EMUs) so the thumbnail doesn't sit flush against
            // the cell's borders — fills the rest of the (col0, rowIdx)-(col1, rowIdx+1) cell.
            int inset = 28000;
            ClientAnchor anchor = new XSSFClientAnchor(inset, inset, -inset, -inset, 0, rowIdx, 1, rowIdx + 1);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            drawing.createPicture(anchor, pictureIdx);
        } catch (Exception e) {
            log.warn("Skipping cover thumbnail for item {}: {}", item.getId(), e.getMessage());
        }
    }

    private byte[] resolveCoverThumbnail(Item item) {
        try {
            byte[] raw = loadCoverBytes(item);
            if (raw == null) return null;
            return downscaleToJpeg(raw, COVER_THUMBNAIL_MAX_PX);
        } catch (Exception e) {
            log.warn("Could not load cover for item {}: {}", item.getId(), e.getMessage());
            return null;
        }
    }

    private byte[] loadCoverBytes(Item item) throws IOException {
        String coverUrl = item.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            ItemPhoto firstPhoto = photoRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId())
                    .stream().findFirst().orElse(null);
            if (firstPhoto == null) return null;
            return storageService.load(firstPhoto.getStorageKey()).getInputStream().readAllBytes();
        }
        if (coverUrl.startsWith("/media/")) {
            String key = coverUrl.substring("/media/".length());
            return storageService.load(key).getInputStream().readAllBytes();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(coverUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (compatible; BOCollections/1.0)")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = COVER_DOWNLOAD_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Downscales to at most maxPx on the long edge and re-encodes as JPEG — embedding full-
     * resolution originals (often several MB each) for an entire collection would balloon the
     * .xlsx file size well past what's reasonable for a spreadsheet attachment. */
    private static byte[] downscaleToJpeg(byte[] original, int maxPx) throws IOException {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(original));
        if (src == null) return null; // unsupported/corrupt image format
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min(1.0, (double) maxPx / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpg", out);
        return out.toByteArray();
    }

    private void writeExcelRow(Row row, CollectionEntry entry, Item item, List<String> metadataKeys, CellStyle style) {
        int col = 1; // column 0 is the Cover image, handled by embedCoverThumbnail
        col = setString(row, col, style, item.getTitle());
        col = setString(row, col, style, item.getSubtitle());
        col = setString(row, col, style, item.getCategory() != null ? item.getCategory().name() : "");
        col = setString(row, col, style, item.getFormat());
        col = setString(row, col, style, item.getPublisher());
        col = setNumeric(row, col, style, item.getReleaseYear());
        col = setString(row, col, style, item.getBarcode());
        col = setString(row, col, style, item.getBarcodeType());
        col = setString(row, col, style, entry.getCondition());
        col = setString(row, col, style, entry.getLocation());
        col = setString(row, col, style, entry.getAcquisitionDate() != null ? entry.getAcquisitionDate().toString() : "");
        col = setNumeric(row, col, style, entry.getPurchasePrice() != null ? entry.getPurchasePrice().doubleValue() : null);
        col = setString(row, col, style, entry.getNotes());
        col = setString(row, col, style, item.getDescription());
        col = setString(row, col, style, item.getExternalSource());

        Map<String, String> metadataValues = metadataValues(item.getMetadata());
        for (String key : metadataKeys) {
            col = setString(row, col, style, metadataValues.get(key));
        }

        setString(row, col, style, item.getCoverUrl());
    }

    private static int setString(Row row, int col, CellStyle style, String value) {
        Cell cell = row.createCell(col);
        cell.setCellValue(nullToEmpty(value));
        cell.setCellStyle(style);
        return col + 1;
    }

    private static int setNumeric(Row row, int col, CellStyle style, Number value) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
        return col + 1;
    }

    /** Every top-level key present in this item's metadata, excluding the internal `_debug`
     * audit-trail key. Best-effort: unparseable metadata just contributes no keys. */
    private java.util.Set<String> metadataKeys(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return java.util.Set.of();
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            if (!root.isObject()) return java.util.Set.of();
            java.util.Set<String> keys = new java.util.HashSet<>();
            root.fieldNames().forEachRemaining(k -> { if (!DEBUG_METADATA_KEY.equals(k)) keys.add(k); });
            return keys;
        } catch (Exception e) {
            log.warn("Skipping unparseable metadata in export: {}", e.getMessage());
            return java.util.Set.of();
        }
    }

    /** Renders each metadata value (arrays joined with commas, scalars as-is) keyed by its raw
     * JSON key, for looking up into whichever per-key column that key was assigned. */
    private Map<String, String> metadataValues(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return Map.of();
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            if (!root.isObject()) return Map.of();
            Map<String, String> values = new LinkedHashMap<>();
            root.fields().forEachRemaining(field -> {
                if (DEBUG_METADATA_KEY.equals(field.getKey())) return;
                String value = jsonValueToString(field.getValue());
                if (!value.isEmpty()) values.put(field.getKey(), value);
            });
            return values;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String jsonValueToString(JsonNode node) {
        if (node.isArray()) {
            List<String> items = new java.util.ArrayList<>();
            node.forEach(n -> items.add(jsonValueToString(n)));
            return String.join(", ", items);
        }
        if (node.isObject() || node.isNull() || node.isMissingNode()) return "";
        return node.asText();
    }

    /** "boxOffice" -> "Box Office", "specialFeatures" -> "Special Features". */
    private static String humanizeKey(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Transactional(readOnly = true)
    public CollectionExport exportJson(Long collectionId, Long userId) {
        Collection collection = findOwnedOrThrow(collectionId, userId);
        List<CollectionEntry> entries = entryRepository.findByCollectionId(collectionId);
        Map<Long, Item> itemsById = loadItems(entries);

        List<ExportEntry> exportEntries = entries.stream()
                .filter(e -> itemsById.containsKey(e.getItemId()))
                .map(e -> toExportEntry(e, itemsById.get(e.getItemId())))
                .toList();

        return CollectionExport.builder()
                .collectionName(collection.getName())
                .description(collection.getDescription())
                .primaryCategory(collection.getPrimaryCategory() != null ? collection.getPrimaryCategory().name() : null)
                .exportedAt(LocalDateTime.now().toString())
                .entries(exportEntries)
                .build();
    }

    private ExportEntry toExportEntry(CollectionEntry entry, Item item) {
        List<ExportPhoto> photos = photoRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId()).stream()
                .map(this::toExportPhoto)
                .filter(java.util.Objects::nonNull)
                .toList();

        ExportItem exportItem = ExportItem.builder()
                .barcode(item.getBarcode())
                .barcodeType(item.getBarcodeType())
                .category(item.getCategory() != null ? item.getCategory().name() : null)
                .format(item.getFormat())
                .title(item.getTitle())
                .subtitle(item.getSubtitle())
                .description(item.getDescription())
                .coverUrl(item.getCoverUrl())
                .releaseYear(item.getReleaseYear())
                .publisher(item.getPublisher())
                .externalId(item.getExternalId())
                .externalSource(item.getExternalSource())
                .metadata(item.getMetadata())
                .photos(photos)
                .build();

        return ExportEntry.builder()
                .condition(entry.getCondition())
                .notes(entry.getNotes())
                .acquisitionDate(entry.getAcquisitionDate())
                .purchasePrice(entry.getPurchasePrice())
                .location(entry.getLocation())
                .item(exportItem)
                .build();
    }

    /** Best-effort: a photo whose underlying file went missing shouldn't fail the whole export —
     * skip it and keep going, same tolerance ScanSessionService's downloadImage applies elsewhere. */
    private ExportPhoto toExportPhoto(ItemPhoto photo) {
        try {
            byte[] bytes = storageService.load(photo.getStorageKey()).getInputStream().readAllBytes();
            String contentType = photo.getStorageKey().endsWith(".png") ? "image/png" : "image/jpeg";
            return ExportPhoto.builder()
                    .angle(photo.getAngle())
                    .sortOrder(photo.getSortOrder())
                    .contentType(contentType)
                    .base64Data(Base64.getEncoder().encodeToString(bytes))
                    .build();
        } catch (IOException | RuntimeException e) {
            log.warn("Skipping export of missing/unreadable photo {}: {}", photo.getStorageKey(), e.getMessage());
            return null;
        }
    }

    /** Always creates NEW items (no dedup against the catalogue) — this is for restoring a
     * backup or bringing in someone else's exported collection wholesale, not merging into an
     * existing one. Best-effort per entry: one bad row is skipped rather than aborting the rest. */
    @Transactional
    public int importJson(Long collectionId, Long userId, CollectionExport data) {
        findOwnedOrThrow(collectionId, userId);
        if (data.getEntries() == null) return 0;

        int imported = 0;
        for (ExportEntry entry : data.getEntries()) {
            ExportItem ei = entry.getItem();
            if (ei == null || ei.getTitle() == null || ei.getTitle().isBlank()) continue;

            try {
                Long itemId = createItemFromExport(ei);
                attachPhotosFromExport(itemId, ei.getPhotos());

                CollectionEntryRequest entryReq = new CollectionEntryRequest();
                entryReq.setItemId(itemId);
                entryReq.setCondition(entry.getCondition());
                entryReq.setNotes(entry.getNotes());
                entryReq.setAcquisitionDate(entry.getAcquisitionDate());
                entryReq.setPurchasePrice(entry.getPurchasePrice());
                entryReq.setLocation(entry.getLocation());
                collectionService.addEntry(collectionId, entryReq, userId);
                imported++;
            } catch (Exception e) {
                log.warn("Skipping import of entry '{}': {}", ei.getTitle(), e.getMessage());
            }
        }
        return imported;
    }

    private Long createItemFromExport(ExportItem ei) {
        ItemRequest itemReq = new ItemRequest();
        itemReq.setBarcode(ei.getBarcode());
        itemReq.setBarcodeType(ei.getBarcodeType());
        itemReq.setCategory(parseCategory(ei.getCategory()));
        itemReq.setFormat(ei.getFormat() != null && !ei.getFormat().isBlank() ? ei.getFormat() : "Other");
        itemReq.setTitle(ei.getTitle());
        itemReq.setSubtitle(ei.getSubtitle());
        itemReq.setDescription(ei.getDescription());
        itemReq.setCoverUrl(ei.getCoverUrl());
        itemReq.setReleaseYear(ei.getReleaseYear());
        itemReq.setPublisher(ei.getPublisher());
        itemReq.setExternalId(ei.getExternalId());
        itemReq.setExternalSource(ei.getExternalSource());
        itemReq.setMetadata(ei.getMetadata());
        return itemService.create(itemReq).getId();
    }

    private void attachPhotosFromExport(Long itemId, List<ExportPhoto> photos) {
        if (photos == null || photos.isEmpty()) return;
        List<ScanDraftPhotoRequest> photoReqs = photos.stream()
                .filter(p -> p.getBase64Data() != null && !p.getBase64Data().isBlank())
                .map(p -> {
                    ScanDraftPhotoRequest r = new ScanDraftPhotoRequest();
                    r.setImageBase64(p.getBase64Data());
                    r.setImageMimeType(p.getContentType() != null ? p.getContentType() : "image/jpeg");
                    r.setAngle(p.getAngle() != null ? p.getAngle() : "FRONT");
                    return r;
                })
                .toList();
        if (!photoReqs.isEmpty()) itemService.addPhotos(itemId, photoReqs);
    }

    private static MediaCategory parseCategory(String category) {
        if (category == null) return MediaCategory.OTHER;
        try {
            return MediaCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return MediaCategory.OTHER;
        }
    }

    private Map<Long, Item> loadItems(List<CollectionEntry> entries) {
        List<Long> ids = entries.stream().map(CollectionEntry::getItemId).distinct().toList();
        return itemRepository.findAllById(ids).stream().collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    private Collection findOwnedOrThrow(Long collectionId, Long userId) {
        return collectionRepository.findByIdAndUserId(collectionId, userId)
                .orElseThrow(() -> new NotFoundException("Collection not found: " + collectionId));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Excel sheet names can't contain \ / * ? : [ ] and are capped at 31 chars. */
    private static String safeSheetName(String name) {
        if (name == null || name.isBlank()) return "Collection";
        String cleaned = name.replaceAll("[\\\\/*?:\\[\\]]", " ");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }
}
