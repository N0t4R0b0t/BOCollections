package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.bocollections.backend.entity.MediaCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * GAME's identification source — mirrors {@link TmdbService}'s role for VIDEO: reached via a
 * title (usually resolved from a barcode by {@link UpcItemDbService} first, since IGDB has no
 * barcode search of its own), returns front cover art only. IGDB's schema has no back-of-box
 * concept at all — {@link TheGamesDbService} supplements with front+back box art separately.
 */
@Service
@Slf4j
public class IgdbService {

    private final RestClient authClient;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${app.igdb.client-secret:}")
    private String clientSecret;

    // Twitch client-credentials tokens last ~64 days with no refresh token — just re-mint via
    // the same call once stale, same as EbayService's shorter-lived token.
    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public IgdbService(@Qualifier("igdbAuthClient") RestClient authClient,
                        @Qualifier("igdbClient") RestClient client,
                        ObjectMapper objectMapper) {
        this.authClient = authClient;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public Optional<LookupResult> searchByTitle(String title) {
        if (!isConfigured() || title == null || title.isBlank()) return Optional.empty();
        try {
            String token = getToken();
            if (token == null) return Optional.empty();

            String body = "search \"" + title.replace("\"", "\\\"") + "\"; "
                    + "fields name,cover.image_id,first_release_date; limit 1;";

            String json = client.post()
                    .uri("/games")
                    .header("Client-ID", clientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray() || root.isEmpty()) return Optional.empty();

            JsonNode game = root.get(0);
            String name = game.path("name").asText(null);
            if (name == null || name.isBlank()) return Optional.empty();

            String imageId = game.path("cover").path("image_id").asText(null);
            String coverUrl = imageId != null
                    ? "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/" + imageId + ".jpg"
                    : null;

            Integer year = null;
            long releaseEpochSeconds = game.path("first_release_date").asLong(0);
            if (releaseEpochSeconds > 0) {
                year = Instant.ofEpochSecond(releaseEpochSeconds).atZone(ZoneOffset.UTC).getYear();
            }

            return Optional.of(LookupResult.builder()
                    .source("IGDB")
                    .category(MediaCategory.GAME)
                    .title(name)
                    .coverUrl(coverUrl)
                    .releaseYear(year)
                    .externalId(game.path("id").asText(null))
                    .build());

        } catch (Exception e) {
            log.warn("IGDB title search failed for \"{}\": {}", title, e.getMessage());
            return Optional.empty();
        }
    }

    private synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) return cachedToken;
        try {
            String json = authClient.post()
                    .uri("/oauth2/token?client_id={id}&client_secret={secret}&grant_type=client_credentials",
                            clientId, clientSecret)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            String token = root.path("access_token").asText(null);
            long expiresInSeconds = root.path("expires_in").asLong(0);
            if (token == null) return null;

            cachedToken = token;
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(0, expiresInSeconds - 60));
            return cachedToken;

        } catch (Exception e) {
            log.debug("IGDB token mint failed: {}", e.getMessage());
            return null;
        }
    }
}
