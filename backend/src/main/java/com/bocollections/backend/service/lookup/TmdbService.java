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
import java.util.Set;

@Service
@Slf4j
public class TmdbService {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Value("${app.tmdb.api-key:}")
    private String apiKey;

    public TmdbService(@Qualifier("tmdbClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Optional<LookupResult> searchByTitle(String title) {
        return searchByTitle(title, Set.of());
    }

    /**
     * @param excludeExternalIds TMDB ids to skip — used when the caller already rejected that
     *                           specific match as wrong (see MetadataLookupService), so the next
     *                           best-ranked candidate from the same search is tried instead.
     */
    public Optional<LookupResult> searchByTitle(String title, Set<String> excludeExternalIds) {
        if (!isConfigured()) return Optional.empty();
        try {
            String json = client.get()
                    .uri("/search/multi?query={q}&api_key={key}&page=1", title, apiKey)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) return Optional.empty();

            // TMDB's own /search/multi ordering favors popularity over title accuracy — a barcode
            // resolving to "Dredd" was matching "Judge Dredd" (1995) because that film has far
            // more votes than "Dredd" (2012), even though the latter is the exact title match.
            // Score title similarity (not just exact-or-nothing) far above popularity, then use
            // popularity as a tiebreaker among similarly-worded candidates; "person" results
            // (actors, not media) never qualify. Similarity (not a binary exact check) matters
            // for cases like "Se7en" (1995) — TMDB's actual title for a barcode that resolves to
            // "Seven" — which a strict equalsIgnoreCase would never credit as the exact match it
            // effectively is, letting an unrelated-but-popular "Seven ___" title win instead.
            JsonNode best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (JsonNode item : results) {
                if ("person".equals(item.path("media_type").asText("movie"))) continue;
                String id = item.path("id").asText(null);
                if (id == null || excludeExternalIds.contains(id)) continue;
                String itemTitle = item.path("title").asText(item.path("name").asText(null));
                if (itemTitle == null) continue;

                double score = titleSimilarity(itemTitle, title) * 1_000_000 + item.path("popularity").asDouble(0);
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
            if (best == null) return Optional.empty();

            String itemTitle = best.path("title").asText(best.path("name").asText(null));
            String releaseDate = best.path("release_date").asText(best.path("first_air_date").asText(null));
            Integer year = null;
            if (releaseDate != null && releaseDate.length() >= 4) {
                try { year = Integer.parseInt(releaseDate.substring(0, 4)); } catch (NumberFormatException ignored) {}
            }

            String posterPath = best.path("poster_path").asText(null);
            String coverUrl = posterPath != null ? "https://image.tmdb.org/t/p/w300" + posterPath : null;
            String tmdbId = best.path("id").asText(null);
            String overview = best.path("overview").asText(null);
            String mediaType = best.path("media_type").asText("movie");

            return Optional.of(LookupResult.builder()
                    .source("TMDB")
                    .category(MediaCategory.VIDEO)
                    // TMDB only knows the *film*, never which physical disc a given barcode is
                    // printed on — hardcoding "DVD" here meant a Blu-ray always got mislabeled.
                    // MetadataLookupService fills this in from UPCitemdb's title tag when that's
                    // available; leaving it null otherwise lets vision's read of the actual box
                    // win instead of a false guess.
                    .title(itemTitle)
                    .description(overview)
                    .releaseYear(year)
                    .coverUrl(coverUrl)
                    .externalId(tmdbId)
                    // Movies only for now — TV's shape (seasons/episodes, no single budget/revenue)
                    // is different enough to warrant its own pass rather than forcing it into the
                    // same fields. A miss here just means no "extra details", never a failed match.
                    .metadata("movie".equals(mediaType) ? fetchExtraMetadata(tmdbId) : null)
                    .build());

        } catch (Exception e) {
            log.warn("TMDB search failed for title {}: {}", title, e.getMessage());
            return Optional.empty();
        }
    }

    /** 1.0 for an exact (case/whitespace-insensitive) match, tapering off with edit distance. */
    private static double titleSimilarity(String a, String b) {
        String x = a.trim().toLowerCase();
        String y = b.trim().toLowerCase();
        int maxLen = Math.max(x.length(), y.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshtein(x, y) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * One extra round-trip for the "extra details" fields the initial search result doesn't
     * carry — director/cast, budget/box office, runtime, tagline, rating, genres, and a handful
     * of alternate poster images (the search result only ever carries TMDB's single default
     * poster; `/movie/{id}` with `images` appended returns the full set so the review screen can
     * offer a real choice instead of just whatever TMDB picked as default). Best-effort: any
     * failure here just means the item is missing its extra details, never a failed match.
     */
    private String fetchExtraMetadata(String tmdbId) {
        try {
            String json = client.get()
                    .uri("/movie/{id}?api_key={key}&append_to_response=credits,images", tmdbId, apiKey)
                    .retrieve()
                    .body(String.class);
            JsonNode movie = objectMapper.readTree(json);

            ObjectNode extra = objectMapper.createObjectNode();
            String tagline = movie.path("tagline").asText(null);
            if (tagline != null && !tagline.isBlank()) extra.put("tagline", tagline);
            long budget = movie.path("budget").asLong(0);
            if (budget > 0) extra.put("budget", budget);
            long revenue = movie.path("revenue").asLong(0);
            if (revenue > 0) extra.put("boxOffice", revenue);
            int runtime = movie.path("runtime").asInt(0);
            if (runtime > 0) extra.put("runtimeMinutes", runtime);
            double rating = movie.path("vote_average").asDouble(0);
            if (rating > 0) extra.put("rating", rating);

            List<String> genres = new ArrayList<>();
            movie.path("genres").forEach(g -> {
                String name = g.path("name").asText(null);
                if (name != null) genres.add(name);
            });
            if (!genres.isEmpty()) extra.putPOJO("genres", genres);

            JsonNode crew = movie.path("credits").path("crew");
            for (JsonNode member : crew) {
                if ("Director".equals(member.path("job").asText(null))) {
                    extra.put("director", member.path("name").asText());
                    break;
                }
            }

            List<String> cast = new ArrayList<>();
            JsonNode castNode = movie.path("credits").path("cast");
            for (int i = 0; i < castNode.size() && i < 8; i++) {
                String name = castNode.get(i).path("name").asText(null);
                if (name != null) cast.add(name);
            }
            if (!cast.isEmpty()) extra.putPOJO("cast", cast);

            // TMDB sorts a movie's posters by vote/language relevance already — take the top couple
            // (skipping the one already used as the default coverUrl, added by the caller) so the
            // draft review screen has a real alternate to choose from instead of a single fixed
            // image. Kept deliberately small: these are all just front-cover poster art — TMDB has
            // no concept of a physical release's back cover, spine, or disc, so piling on more of
            // them just crowds the photo strip with near-duplicate covers instead of adding real
            // variety (that only comes from the user's own FRONT/BACK/SPINE/DISC captures).
            List<String> posterOptions = new ArrayList<>();
            JsonNode posters = movie.path("images").path("posters");
            for (int i = 0; i < posters.size() && posterOptions.size() < 2; i++) {
                String path = posters.get(i).path("file_path").asText(null);
                if (path != null) posterOptions.add("https://image.tmdb.org/t/p/w300" + path);
            }
            if (!posterOptions.isEmpty()) extra.putPOJO("posterOptions", posterOptions);

            return extra.isEmpty() ? null : objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            log.debug("TMDB extra metadata fetch failed for id {}: {}", tmdbId, e.getMessage());
            return null;
        }
    }
}
