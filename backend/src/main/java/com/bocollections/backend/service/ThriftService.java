package com.bocollections.backend.service;

import com.bocollections.backend.dto.TasteProfile;
import com.bocollections.backend.dto.thrift.ThriftItem;
import com.bocollections.backend.dto.thrift.ThriftItem.BoundingBox;
import com.bocollections.backend.dto.thrift.ThriftScanRequest;
import com.bocollections.backend.dto.thrift.ThriftScanResponse;
import com.bocollections.backend.entity.CollectionEntry;
import com.bocollections.backend.entity.Confidence;
import com.bocollections.backend.entity.Item;
import com.bocollections.backend.entity.MediaCategory;
import com.bocollections.backend.entity.OwnedStatus;
import com.bocollections.backend.config.VisionTask;
import com.bocollections.backend.repository.CollectionEntryRepository;
import com.bocollections.backend.repository.ItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ThriftService {

    private final VisualScanService visualScanService;
    private final ItemRepository itemRepository;
    private final CollectionEntryRepository collectionEntryRepository;
    private final TasteProfileService tasteProfileService;
    private final ObjectMapper objectMapper;

    private static final String THRIFT_PROMPT = """
            You are helping a collector at a thrift store, record fair, or flea market.
            Look at this image of a shelf and identify every visible physical media item
            (CDs, vinyl records, books, DVDs, video games, magazines, etc.).
            For each item you can see, provide its position as a bounding box with relative
            coordinates (0.0 to 1.0, origin at top-left).
            Respond ONLY with valid JSON, nothing else:
            {
              "items": [
                {
                  "title": "exact title as printed on the item",
                  "artist_or_author": "artist, band, or author name, or null",
                  "category": "PRINT" or "AUDIO" or "VIDEO" or "GAME" or "OTHER",
                  "format": "CD" or "Vinyl LP" or "Book" or "DVD" or "Blu-ray" or "Game" or "Magazine" or other,
                  "bbox": {"x": 0.12, "y": 0.05, "w": 0.08, "h": 0.90},
                  "confidence": "HIGH" or "MEDIUM" or "LOW"
                }
              ]
            }
            Only include items you can actually read or clearly identify. Omit blurry or unreadable spines.
            """;

    public ThriftScanResponse scan(ThriftScanRequest req, Long userId) {
        List<RawItem> rawItems = identifyItems(req.getImageBase64(), req.getImageMimeType());
        // Fetched once per request rather than once per raw item — the profile can't change
        // mid-request, so a busy shelf with a dozen not-owned items would otherwise trigger a
        // dozen redundant freshness checks (and, on a cold cache, full recomputes) for identical data.
        TasteProfile profile = tasteProfileService.getOrCompute(userId);
        List<ThriftItem> items = rawItems.stream()
                .map(raw -> crossReference(raw, req.getCollectionIds(), userId, profile))
                .toList();
        return ThriftScanResponse.builder().items(items).build();
    }

    /**
     * Single shared classification path for both shelf-mode's per-raw-item loop and held-item
     * mode's classify endpoint. When existingItemId/ownedInCollections are supplied (held-item
     * mode after a confirmed barcode lookup), skips fuzzy title matching entirely and trusts the
     * exact catalogue link — a strictly better signal than re-deriving ownership by title, and
     * avoids a wrong match when two different items happen to share a title. DIFFERENT_VERSION is
     * structurally shelf-mode-only: a barcode identifies an exact edition, so a barcode-confirmed
     * held item is only ever OWNED/NOT_OWNED/INTERESTING.
     */
    public ClassificationResult classifyItem(
            String title, MediaCategory category, String format, String publisher, Integer releaseYear,
            Long existingItemId, List<Long> ownedInCollections,
            List<Long> collectionIds, Long userId) {
        return classifyItem(title, category, format, publisher, releaseYear, existingItemId,
                ownedInCollections, collectionIds, userId, null);
    }

    private ClassificationResult classifyItem(
            String title, MediaCategory category, String format, String publisher, Integer releaseYear,
            Long existingItemId, List<Long> ownedInCollections,
            List<Long> collectionIds, Long userId, TasteProfile cachedProfile) {

        if (existingItemId != null) {
            ClassificationResult trusted = classifyByTrustedItemId(
                    existingItemId, title, category, format, publisher, releaseYear, collectionIds, userId, cachedProfile);
            if (trusted != null) return trusted;
            // The client's existingItemId claim didn't check out (no such item, or its title
            // doesn't correspond to what was actually identified) — fall through to the normal
            // fuzzy title-based path below rather than trusting an unverified assertion.
        }

        String normalized = normalizeTitle(title);
        List<Item> owned = itemRepository.findOwnedByNormalizedTitle(userId, normalized, collectionIds);

        if (owned.isEmpty()) {
            return notOwnedOrInteresting(category, format, publisher, releaseYear, userId, cachedProfile);
        }

        Item exactMatch = owned.stream()
                .filter(i -> format != null && formatsMatch(i.getFormat(), format))
                .findFirst()
                .orElse(null);

        if (exactMatch != null) {
            return new ClassificationResult(OwnedStatus.OWNED, exactMatch.getId());
        }
        return new ClassificationResult(OwnedStatus.DIFFERENT_VERSION, owned.getFirst().getId());
    }

    /**
     * Held-item mode's client claims "this barcode-confirmed item is #N, owned in collections
     * X" — verified here rather than trusted outright, since a buggy or malicious client could
     * otherwise assert an arbitrary itemId as OWNED with no server-side check at all. Returns
     * null (not a ClassificationResult) when the claim itself doesn't hold up, signalling the
     * caller to fall back to the normal fuzzy title lookup instead.
     */
    private ClassificationResult classifyByTrustedItemId(
            Long existingItemId, String title, MediaCategory category, String format, String publisher,
            Integer releaseYear, List<Long> collectionIds, Long userId, TasteProfile cachedProfile) {
        Item item = itemRepository.findById(existingItemId).orElse(null);
        if (item == null || !titlesCorrespond(item.getTitle(), title)) return null;

        List<Long> ownedCollectionIds = collectionEntryRepository.findByUserIdAndItemId(userId, existingItemId)
                .stream().map(CollectionEntry::getCollectionId).toList();
        boolean owned = collectionIds == null || collectionIds.isEmpty()
                ? !ownedCollectionIds.isEmpty()
                : ownedCollectionIds.stream().anyMatch(collectionIds::contains);
        if (owned) {
            return new ClassificationResult(OwnedStatus.OWNED, existingItemId);
        }
        return notOwnedOrInteresting(category, format, publisher, releaseYear, userId, cachedProfile);
    }

    /** Tolerant like formatsMatch, not exact — barcode-sourced and OCR/vision-read titles can
     * differ slightly in punctuation or a subtitle. */
    private boolean titlesCorrespond(String itemTitle, String claimedTitle) {
        String a = normalizeTitle(itemTitle);
        String b = normalizeTitle(claimedTitle);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private ClassificationResult notOwnedOrInteresting(
            MediaCategory category, String format, String publisher, Integer releaseYear,
            Long userId, TasteProfile cachedProfile) {
        TasteProfile profile = cachedProfile != null ? cachedProfile : tasteProfileService.getOrCompute(userId);
        boolean interesting = tasteProfileService.scoreInteresting(profile, category, format, publisher, releaseYear);
        return new ClassificationResult(interesting ? OwnedStatus.INTERESTING : OwnedStatus.NOT_OWNED, null);
    }

    /** Lowercase, strip non-alphanumeric, collapse whitespace — must match ItemRepository's SQL-side normalization exactly. */
    public String normalizeTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private List<RawItem> identifyItems(String imageBase64, String mimeType) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
            MimeType mime = visualScanService.parseMime(mimeType);

            String raw = visualScanService.callVisionModel(
                    THRIFT_PROMPT, List.of(new Media(mime, new ByteArrayResource(imageBytes))), VisionTask.SHELF);

            return parseRawItems(raw);

        } catch (Exception e) {
            log.warn("Thrift vision scan failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RawItem> parseRawItems(String raw) {
        try {
            String json = visualScanService.extractJson(raw);
            JsonNode root = objectMapper.readTree(json);
            JsonNode itemsNode = root.path("items");
            if (!itemsNode.isArray()) return List.of();

            List<RawItem> result = new ArrayList<>();
            for (JsonNode node : itemsNode) {
                String title = node.path("title").asText(null);
                if (title == null || title.isBlank()) continue;

                JsonNode bboxNode = node.path("bbox");
                BoundingBox bbox = BoundingBox.builder()
                        .x(bboxNode.path("x").asDouble(0))
                        .y(bboxNode.path("y").asDouble(0))
                        .w(bboxNode.path("w").asDouble(0.1))
                        .h(bboxNode.path("h").asDouble(0.1))
                        .build();

                MediaCategory category = null;
                try { category = MediaCategory.valueOf(node.path("category").asText("")); }
                catch (IllegalArgumentException ignored) {}

                Confidence confidence;
                try { confidence = Confidence.valueOf(node.path("confidence").asText("LOW")); }
                catch (IllegalArgumentException e) { confidence = Confidence.LOW; }

                result.add(new RawItem(
                        title,
                        nullIfBlank(node.path("artist_or_author").asText(null)),
                        category,
                        nullIfBlank(node.path("format").asText(null)),
                        bbox,
                        confidence));
            }
            return result;

        } catch (Exception e) {
            log.debug("Could not parse thrift vision response: {}", raw);
            return List.of();
        }
    }

    private ThriftItem crossReference(RawItem raw, List<Long> collectionIds, Long userId, TasteProfile profile) {
        ClassificationResult result = classifyItem(
                raw.title(), raw.category(), raw.format(), null, null,
                null, null, collectionIds, userId, profile);

        return ThriftItem.builder()
                .title(raw.title())
                .artistOrAuthor(raw.artistOrAuthor())
                .category(raw.category())
                .format(raw.format())
                .bbox(raw.bbox())
                .confidence(raw.confidence())
                .ownedStatus(result.ownedStatus())
                .itemId(result.itemId())
                .build();
    }

    /** Loose format comparison: normalise to lowercase and check shared keywords. */
    private boolean formatsMatch(String ownedFormat, String identifiedFormat) {
        if (ownedFormat == null || identifiedFormat == null) return false;
        String a = ownedFormat.toLowerCase();
        String b = identifiedFormat.toLowerCase();
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        if ((a.contains("vinyl") || a.contains("lp")) && (b.contains("vinyl") || b.contains("lp"))) return true;
        if (a.contains("blu") && b.contains("blu")) return true;
        return false;
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    private record RawItem(String title, String artistOrAuthor, MediaCategory category, String format, BoundingBox bbox, Confidence confidence) {}

    public record ClassificationResult(OwnedStatus ownedStatus, Long itemId) {}
}
