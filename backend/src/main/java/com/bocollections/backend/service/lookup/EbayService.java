package com.bocollections.backend.service.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/**
 * Pure image supplement, not an identification source — eBay listing titles aren't reliable
 * enough to trust as canonical title/category/format (sellers write those freely), but listing
 * photos are real photos of the actual physical product (front, back, disc), which UPCitemdb's
 * retailer-scraped photos often miss. Used alongside {@link UpcItemDbService}'s images, never
 * as a {@link com.bocollections.backend.dto.LookupResult} of its own.
 */
@Service
@Slf4j
public class EbayService {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Value("${app.ebay.client-id:}")
    private String clientId;

    @Value("${app.ebay.client-secret:}")
    private String clientSecret;

    // App-level (client-credentials) token — no user login involved, so a single cached token
    // is shared across all callers. eBay tokens are ~2h; refreshed a minute early to avoid a
    // request racing the exact expiry instant.
    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public EbayService(@Qualifier("ebayClient") RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public List<String> lookupImages(String barcode) {
        if (!isConfigured()) return List.of();
        try {
            String token = getToken();
            if (token == null) return List.of();

            String json = client.get()
                    .uri("/buy/browse/v1/item_summary/search?gtin={gtin}&limit=3", barcode)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.get("itemSummaries");
            if (items == null || !items.isArray()) return List.of();

            Set<String> images = new LinkedHashSet<>();
            for (JsonNode item : items) {
                String primary = item.path("image").path("imageUrl").asText(null);
                if (primary != null && !primary.isBlank()) images.add(primary);
                for (JsonNode extra : item.path("additionalImages")) {
                    String url = extra.path("imageUrl").asText(null);
                    if (url != null && !url.isBlank()) images.add(url);
                }
                if (images.size() >= 6) break;
            }
            return new ArrayList<>(images).subList(0, Math.min(images.size(), 6));

        } catch (Exception e) {
            log.debug("eBay image lookup failed for barcode {}: {}", barcode, e.getMessage());
            return List.of();
        }
    }

    private synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) return cachedToken;
        try {
            String credentials = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

            String json = client.post()
                    .uri("/identity/v1/oauth2/token")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials&scope=https://api.ebay.com/oauth/api_scope")
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
            log.debug("eBay token mint failed: {}", e.getMessage());
            return null;
        }
    }
}
