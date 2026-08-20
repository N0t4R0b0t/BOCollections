package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.bocollections.backend.entity.MediaCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class DiscogsService {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Value("${app.discogs.token:}")
    private String token;

    public DiscogsService(@Qualifier("discogsClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    public Optional<LookupResult> lookupByBarcode(String barcode) {
        if (!isConfigured()) return Optional.empty();
        try {
            String json = client.get()
                    .uri("/database/search?barcode={barcode}&token={token}&per_page=1", barcode, token)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) return Optional.empty();

            JsonNode result = results.get(0);

            String title = result.path("title").asText(null);
            if (title == null) return Optional.empty();

            // Discogs title often contains "Artist - Title"
            String artist = null;
            if (title.contains(" - ")) {
                String[] parts = title.split(" - ", 2);
                artist = parts[0].trim();
                title = parts[1].trim();
            }

            String coverUrl = result.path("cover_image").asText(null);
            if (coverUrl != null && coverUrl.contains("spacer.gif")) coverUrl = null;

            // Format
            String format = "Vinyl LP";
            JsonNode formats = result.get("format");
            if (formats != null && formats.isArray() && !formats.isEmpty()) {
                format = formats.get(0).asText(format);
            }

            // Label
            String label = null;
            JsonNode labels = result.get("label");
            if (labels != null && labels.isArray() && !labels.isEmpty()) {
                label = labels.get(0).asText(null);
            }

            Integer year = null;
            String yearStr = result.path("year").asText(null);
            if (yearStr != null) {
                try { year = Integer.parseInt(yearStr); } catch (NumberFormatException ignored) {}
            }

            String discogId = result.path("id").asText(null);

            return Optional.of(LookupResult.builder()
                    .source("DISCOGS")
                    .category(MediaCategory.AUDIO)
                    .format(format)
                    .title(title)
                    .publisher(label != null ? label : artist)
                    .releaseYear(year)
                    .coverUrl(coverUrl)
                    .externalId(discogId)
                    .metadata(fetchExtraMetadata(discogId, artist))
                    .build());

        } catch (Exception e) {
            log.warn("Discogs lookup failed for barcode {}: {}", barcode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * One extra round-trip for the "extra details" fields the barcode search result doesn't
     * carry — full tracklist, genres/styles, pressing country, catalog number. Best-effort: any
     * failure here just means the item is missing its extra details, never a failed match.
     */
    private String fetchExtraMetadata(String discogId, String artist) {
        if (discogId == null) return artist != null ? "{\"artist\":\"" + escapeJson(artist) + "\"}" : null;
        try {
            String json = client.get()
                    .uri("/releases/{id}?token={token}", discogId, token)
                    .retrieve()
                    .body(String.class);
            JsonNode release = objectMapper.readTree(json);

            ObjectNode extra = objectMapper.createObjectNode();
            if (artist != null) extra.put("artist", artist);

            List<String> genres = new ArrayList<>();
            release.path("genres").forEach(g -> genres.add(g.asText()));
            release.path("styles").forEach(g -> genres.add(g.asText()));
            if (!genres.isEmpty()) extra.putPOJO("genres", genres);

            String country = release.path("country").asText(null);
            if (country != null) extra.put("country", country);

            JsonNode labels = release.path("labels");
            if (labels.isArray() && !labels.isEmpty()) {
                String catNo = labels.get(0).path("catno").asText(null);
                if (catNo != null && !catNo.isBlank() && !"none".equalsIgnoreCase(catNo)) extra.put("catalogNumber", catNo);
            }

            List<String> tracklist = new ArrayList<>();
            release.path("tracklist").forEach(t -> {
                String trackTitle = t.path("title").asText(null);
                if (trackTitle != null && !trackTitle.isBlank()) tracklist.add(trackTitle);
            });
            if (!tracklist.isEmpty()) extra.putPOJO("tracklist", tracklist);

            return extra.isEmpty() ? null : objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            log.debug("Discogs extra metadata fetch failed for release {}: {}", discogId, e.getMessage());
            return artist != null ? "{\"artist\":\"" + escapeJson(artist) + "\"}" : null;
        }
    }

    /**
     * Best-effort fallback for items with no barcode: looks up a representative release by
     * title alone. Used only to fill in generic imagery/metadata for an otherwise-unmatched
     * draft — never claims to have found the user's exact physical copy/edition.
     */
    public Optional<LookupResult> searchByTitle(String title) {
        if (!isConfigured()) return Optional.empty();
        try {
            String json = client.get()
                    .uri("/database/search?q={title}&type=release&token={token}&per_page=1", title, token)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) return Optional.empty();

            JsonNode result = results.get(0);

            String foundTitle = result.path("title").asText(null);
            if (foundTitle == null) return Optional.empty();

            String artist = null;
            if (foundTitle.contains(" - ")) {
                String[] parts = foundTitle.split(" - ", 2);
                artist = parts[0].trim();
                foundTitle = parts[1].trim();
            }

            String coverUrl = result.path("cover_image").asText(null);
            if (coverUrl != null && coverUrl.contains("spacer.gif")) coverUrl = null;

            String format = "Vinyl LP";
            JsonNode formats = result.get("format");
            if (formats != null && formats.isArray() && !formats.isEmpty()) {
                format = formats.get(0).asText(format);
            }

            String label = null;
            JsonNode labels = result.get("label");
            if (labels != null && labels.isArray() && !labels.isEmpty()) {
                label = labels.get(0).asText(null);
            }

            Integer year = null;
            String yearStr = result.path("year").asText(null);
            if (yearStr != null) {
                try { year = Integer.parseInt(yearStr); } catch (NumberFormatException ignored) {}
            }

            return Optional.of(LookupResult.builder()
                    .source("DISCOGS")
                    .category(MediaCategory.AUDIO)
                    .format(format)
                    .title(foundTitle)
                    .publisher(label != null ? label : artist)
                    .releaseYear(year)
                    .coverUrl(coverUrl)
                    .build());

        } catch (Exception e) {
            log.warn("Discogs title search failed for \"{}\": {}", title, e.getMessage());
            return Optional.empty();
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
