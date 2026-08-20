package com.bocollections.backend.service.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Box-art supplement for GAME — the only one of this app's three game-related image sources
 * that models front and back box art as distinct records (its `side` field), so it fills the
 * gap {@link IgdbService} structurally can't (IGDB has no back-of-box concept at all). Access
 * to an API key requires a manually-approved forum request at thegamesdb.net, so
 * {@link #isConfigured()} is expected to be false until that's granted — same optional shape
 * as {@link DiscogsService}'s token.
 */
@Service
@Slf4j
public class TheGamesDbService {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Value("${app.thegamesdb.api-key:}")
    private String apiKey;

    public TheGamesDbService(@Qualifier("theGamesDbClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public List<String> searchBoxArt(String title) {
        if (!isConfigured() || title == null || title.isBlank()) return List.of();
        try {
            String json = client.get()
                    .uri("/v1.1/Games/ByGameName?apikey={key}&name={name}&include=boxart", apiKey, title)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode games = root.path("data").path("games");
            if (!games.isArray() || games.isEmpty()) return List.of();

            String gameId = games.get(0).path("id").asText(null);
            if (gameId == null) return List.of();

            String largeBaseUrl = root.path("include").path("boxart").path("base_url").path("large").asText(null);
            if (largeBaseUrl == null) return List.of();

            JsonNode boxartByGame = root.path("include").path("boxart").path("data").path(gameId);
            if (!boxartByGame.isArray()) return List.of();

            // Front first, then back — matches the priority order every other image list in
            // this app puts real front-cover-shaped photos ahead of secondary angles.
            List<String> front = new ArrayList<>();
            List<String> back = new ArrayList<>();
            for (JsonNode entry : boxartByGame) {
                String filename = entry.path("filename").asText(null);
                if (filename == null) continue;
                String side = entry.path("side").asText("");
                if ("front".equals(side)) front.add(largeBaseUrl + filename);
                else if ("back".equals(side)) back.add(largeBaseUrl + filename);
            }
            List<String> result = new ArrayList<>(front);
            result.addAll(back);
            return result;

        } catch (Exception e) {
            log.debug("TheGamesDB box art search failed for \"{}\": {}", title, e.getMessage());
            return List.of();
        }
    }
}
