package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.bocollections.backend.entity.MediaCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class MusicBrainzService {

    // MusicBrainz enforces 1 req/sec per IP.
    private final Throttle throttle = new Throttle(1_100); // 10% buffer over the 1 s limit

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public MusicBrainzService(@Qualifier("musicBrainzClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public Optional<LookupResult> lookupByBarcode(String barcode) {
        try {
            throttle.await();

            String json = client.get()
                    .uri("/release?query=barcode:{barcode}&fmt=json&limit=1", barcode)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode releases = root.get("releases");
            if (releases == null || !releases.isArray() || releases.isEmpty()) return Optional.empty();

            JsonNode release = releases.get(0);

            // Only take results with high enough score
            int score = release.path("score").asInt(0);
            if (score < 70) return Optional.empty();

            String title = release.path("title").asText(null);
            if (title == null) return Optional.empty();

            // Artist credit → use as publisher/label stand-in for metadata
            String artist = null;
            JsonNode credits = release.get("artist-credit");
            if (credits != null && credits.isArray() && !credits.isEmpty()) {
                artist = credits.get(0).path("name").asText(null);
                if (artist == null) artist = credits.get(0).path("artist").path("name").asText(null);
            }

            // Label
            String label = null;
            JsonNode labelInfo = release.get("label-info");
            if (labelInfo != null && labelInfo.isArray() && !labelInfo.isEmpty()) {
                label = labelInfo.get(0).path("label").path("name").asText(null);
            }

            // Format
            String format = "CD";
            JsonNode media = release.get("media");
            if (media != null && media.isArray() && !media.isEmpty()) {
                String mbFormat = media.get(0).path("format").asText(null);
                if (mbFormat != null) format = mbFormat; // "CD", "Vinyl", "Cassette", "MiniDisc" etc.
            }

            // Year
            Integer year = null;
            String date = release.path("date").asText(null);
            if (date != null && date.length() >= 4) {
                try { year = Integer.parseInt(date.substring(0, 4)); } catch (NumberFormatException ignored) {}
            }

            // MusicBrainz release ID for cover art lookup
            String mbid = release.path("id").asText(null);
            String coverUrl = mbid != null
                    ? "https://coverartarchive.org/release/" + mbid + "/front-250"
                    : null;

            return Optional.of(LookupResult.builder()
                    .source("MUSICBRAINZ")
                    .category(MediaCategory.AUDIO)
                    .format(format)
                    .title(title)
                    .publisher(label != null ? label : artist)
                    .releaseYear(year)
                    .coverUrl(coverUrl)
                    .externalId(mbid)
                    .metadata(fetchExtraMetadata(mbid, artist))
                    .build());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MusicBrainz lookup failed for barcode {}: {}", barcode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * One extra (throttled, same as the main lookup) round-trip for the full tracklist — the
     * barcode search response doesn't include recordings. Best-effort: any failure here just
     * means the item is missing its extra details, never a failed match.
     */
    private String fetchExtraMetadata(String mbid, String artist) {
        ObjectNode extra = objectMapper.createObjectNode();
        if (artist != null) extra.put("artist", artist);
        if (mbid == null) return extra.isEmpty() ? null : writeOrNull(extra);
        try {
            throttle.await();
            String json = client.get()
                    .uri("/release/{mbid}?inc=recordings&fmt=json", mbid)
                    .retrieve()
                    .body(String.class);
            JsonNode release = objectMapper.readTree(json);

            List<String> tracklist = new ArrayList<>();
            for (JsonNode medium : release.path("media")) {
                for (JsonNode track : medium.path("tracks")) {
                    String trackTitle = track.path("title").asText(null);
                    if (trackTitle != null && !trackTitle.isBlank()) tracklist.add(trackTitle);
                }
            }
            if (!tracklist.isEmpty()) extra.putPOJO("tracklist", tracklist);

            return extra.isEmpty() ? null : writeOrNull(extra);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return extra.isEmpty() ? null : writeOrNull(extra);
        } catch (Exception e) {
            log.debug("MusicBrainz extra metadata fetch failed for release {}: {}", mbid, e.getMessage());
            return extra.isEmpty() ? null : writeOrNull(extra);
        }
    }

    private String writeOrNull(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }
}
