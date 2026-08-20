package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.bocollections.backend.entity.MediaCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class OpenLibraryService {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public OpenLibraryService(@Qualifier("openLibraryClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public Optional<LookupResult> lookupByIsbn(String isbn) {
        try {
            String bibKey = "ISBN:" + isbn;
            String json = client.get()
                    .uri("/api/books?bibkeys={key}&jscmd=details&format=json", bibKey)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode entry = root.get(bibKey);
            if (entry == null || entry.isMissingNode()) return Optional.empty();

            JsonNode details = entry.get("details");
            if (details == null) return Optional.empty();

            String title = details.path("title").asText(null);
            if (title == null) return Optional.empty();

            String publisher = null;
            if (details.has("publishers") && details.get("publishers").isArray() && details.get("publishers").size() > 0) {
                publisher = details.get("publishers").get(0).asText();
            }

            Integer year = null;
            String publishDate = details.path("publish_date").asText(null);
            if (publishDate != null) {
                try {
                    year = Integer.parseInt(publishDate.replaceAll(".*?(\\d{4}).*", "$1"));
                } catch (NumberFormatException ignored) {}
            }

            String coverUrl = null;
            if (details.has("covers") && details.get("covers").isArray() && details.get("covers").size() > 0) {
                long coverId = details.get("covers").get(0).asLong();
                coverUrl = "https://covers.openlibrary.org/b/id/" + coverId + "-L.jpg";
            } else if (entry.has("thumbnail_url")) {
                coverUrl = entry.get("thumbnail_url").asText().replace("-S.jpg", "-L.jpg");
            }

            String description = null;
            if (details.has("description")) {
                JsonNode desc = details.get("description");
                description = desc.isTextual() ? desc.asText() : desc.path("value").asText(null);
            }

            String subtitle = details.path("subtitle").asText(null);

            return Optional.of(LookupResult.builder()
                    .source("OPEN_LIBRARY")
                    .category(MediaCategory.PRINT)
                    .format("Book")
                    .title(title)
                    .subtitle(subtitle)
                    .description(description)
                    .publisher(publisher)
                    .releaseYear(year)
                    .coverUrl(coverUrl)
                    .externalId(isbn)
                    .metadata(buildExtraMetadata(details))
                    .build());

        } catch (Exception e) {
            log.warn("Open Library lookup failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * "Extra details" fields already present on this same details response — no further
     * round-trip needed, unlike TMDB/Discogs/MusicBrainz. Note the real field is `author`
     * (singular), not `authors` — a previous version of this method checked the wrong key and
     * silently never populated it.
     */
    private String buildExtraMetadata(JsonNode details) {
        ObjectNode extra = objectMapper.createObjectNode();

        List<String> authors = new ArrayList<>();
        details.path("author").forEach(a -> {
            String name = a.isTextual() ? a.asText() : a.path("name").asText(null);
            if (name != null && !name.isBlank()) authors.add(name);
        });
        if (!authors.isEmpty()) extra.putPOJO("authors", authors);

        int pageCount = details.path("number_of_pages").asInt(0);
        if (pageCount > 0) extra.put("pageCount", pageCount);

        List<String> subjects = new ArrayList<>();
        details.path("subjects").forEach(s -> {
            String name = s.isTextual() ? s.asText() : s.path("name").asText(null);
            if (name != null && !name.isBlank()) subjects.add(name);
        });
        if (!subjects.isEmpty()) extra.putPOJO("subjects", subjects);

        String series = details.path("series").isArray() && !details.path("series").isEmpty()
                ? details.path("series").get(0).asText(null)
                : details.path("series").asText(null);
        if (series != null && !series.isBlank()) extra.put("series", series);

        try {
            return extra.isEmpty() ? null : objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Best-effort fallback for items with no barcode: looks up a representative cover/edition
     * by title alone. Used only to fill in generic imagery/metadata for an otherwise-unmatched
     * draft — never claims to have found the user's exact physical copy.
     */
    public Optional<LookupResult> searchByTitle(String title) {
        try {
            String json = client.get()
                    .uri("/search.json?title={title}&limit=1", title)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode docs = root.get("docs");
            if (docs == null || !docs.isArray() || docs.isEmpty()) return Optional.empty();

            JsonNode doc = docs.get(0);
            String foundTitle = doc.path("title").asText(null);
            if (foundTitle == null) return Optional.empty();

            String publisher = null;
            JsonNode publishers = doc.get("publisher");
            if (publishers != null && publishers.isArray() && !publishers.isEmpty()) {
                publisher = publishers.get(0).asText();
            }

            Integer year = null;
            JsonNode yearNode = doc.get("first_publish_year");
            if (yearNode != null && !yearNode.isNull()) year = yearNode.asInt();

            String coverUrl = null;
            JsonNode coverId = doc.get("cover_i");
            if (coverId != null && !coverId.isNull()) {
                coverUrl = "https://covers.openlibrary.org/b/id/" + coverId.asLong() + "-L.jpg";
            }

            String metadata = doc.has("author_name") ? objectMapper.writeValueAsString(doc.get("author_name")) : null;

            return Optional.of(LookupResult.builder()
                    .source("OPEN_LIBRARY")
                    .category(MediaCategory.PRINT)
                    .format("Book")
                    .title(foundTitle)
                    .publisher(publisher)
                    .releaseYear(year)
                    .coverUrl(coverUrl)
                    .metadata(metadata)
                    .build());

        } catch (Exception e) {
            log.warn("Open Library title search failed for \"{}\": {}", title, e.getMessage());
            return Optional.empty();
        }
    }
}
