package com.bocollections.backend.service.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a barcode to a bare product title via UPCitemdb's no-key trial endpoint — a general
 * retail barcode database, not video-specific. Only identity (the title), not full metadata:
 * {@link TmdbService#searchByTitle} supplies the actual poster/description/release year from
 * that title. The trial tier is rate-limited (~100/day, 1 req/sec), so calls are throttled the
 * same way {@link MusicBrainzService} throttles MusicBrainz.
 */
@Service
@Slf4j
public class UpcItemDbService {

    private final Throttle throttle = new Throttle(1_100); // 10% buffer over the 1 s limit

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public UpcItemDbService(@Qualifier("upcItemDbClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * @param brand  UPCitemdb's `brand` field — the distributor/publisher of this specific
     *               release (e.g. "Mill Creek", "Criterion Collection"), distinct from the
     *               studio/label TMDB or Discogs report. Null when UPCitemdb didn't have one.
     * @param format The physical format tag UPCitemdb's title embeds (e.g. "(Blu-ray )") — the
     *               only source in this whole lookup chain that actually knows which physical
     *               edition this barcode is for. TMDB only knows the *film*, not the disc it's
     *               printed on, so this is what should decide the result's format, not a guess.
     *               Null when the title didn't carry a recognizable tag.
     * @param images UPCitemdb's `images` array — real photos of *this specific physical product*
     *               (front cover, sometimes back cover/disc) pulled from retailer listings, unlike
     *               TMDB's posters which are official promotional art for the film only. Empty
     *               when UPCitemdb had none.
     */
    public record UpcItemDbResult(String title, String brand, String format, List<String> images) {}

    private static final Pattern FORMAT_TAG =
            Pattern.compile("(?i)\\s*\\((DVD|Blu-ray|4K(?:\\s*UHD)?|VHS|CD|Vinyl|Widescreen|Full Screen)\\s*\\)\\s*$");

    public Optional<UpcItemDbResult> lookup(String barcode) {
        try {
            throttle.await();

            String json = client.get()
                    .uri("/lookup?upc={upc}", barcode)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) return Optional.empty();

            JsonNode item = items.get(0);
            String title = item.path("title").asText(null);
            if (title == null || title.isBlank()) return Optional.empty();

            String brand = item.path("brand").asText(null);
            List<String> images = new ArrayList<>();
            JsonNode imagesNode = item.path("images");
            if (imagesNode.isArray()) {
                for (JsonNode img : imagesNode) {
                    String url = img.asText(null);
                    if (url != null && !url.isBlank()) images.add(url);
                }
            }
            return Optional.of(new UpcItemDbResult(
                    cleanTitle(title),
                    (brand == null || brand.isBlank()) ? null : brand,
                    extractFormat(title),
                    images));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("UPCitemdb lookup failed for barcode {}: {}", barcode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * UPCitemdb's DVD/Blu-ray titles come back retailer-formatted, e.g.
     * "Coyote Ugly (DVD)  Mill Creek  Comedy" or "Dredd (Blu-ray )" — a format tag in
     * parentheses plus, often, a double-space-separated distributor/genre suffix. Searching
     * TMDB with that noise attached returns zero results even though the bare title matches
     * cleanly (confirmed directly: "Coyote Ugly" finds it, the raw string above doesn't).
     */
    private static String cleanTitle(String raw) {
        String title = raw;
        int doubleSpace = title.indexOf("  ");
        if (doubleSpace > 0) title = title.substring(0, doubleSpace);
        title = FORMAT_TAG.matcher(title).replaceAll("");
        return title.trim();
    }

    /** Maps the tag captured by {@link #FORMAT_TAG} to this app's canonical VIDEO format values
     * — "Widescreen"/"Full Screen" are aspect-ratio notes, not a physical format, so those (and
     * anything the regex didn't match at all) intentionally return null rather than a guess. */
    private static String extractFormat(String raw) {
        // Only the double-space-truncated portion carries the tag reliably — matching against
        // the full raw string risks a genre/distributor suffix after the tag throwing off the
        // trailing-anchor regex.
        String beforeSuffix = raw;
        int doubleSpace = beforeSuffix.indexOf("  ");
        if (doubleSpace > 0) beforeSuffix = beforeSuffix.substring(0, doubleSpace);

        Matcher m = FORMAT_TAG.matcher(beforeSuffix);
        if (!m.find()) return null;
        String tag = m.group(1).toLowerCase();
        return switch (tag) {
            case "dvd" -> "DVD";
            case "blu-ray" -> "Blu-ray";
            case "vhs" -> "VHS";
            default -> tag.startsWith("4k") ? "4K UHD" : null;
        };
    }
}
