package com.bocollections.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean("openLibraryClient")
    public RestClient openLibraryClient() {
        return RestClient.builder()
                .baseUrl("https://openlibrary.org")
                .defaultHeader("User-Agent", "BOCollections/1.0 (contact@bocollections.local)")
                .build();
    }

    @Bean("musicBrainzClient")
    public RestClient musicBrainzClient() {
        return RestClient.builder()
                .baseUrl("https://musicbrainz.org/ws/2")
                .defaultHeader("User-Agent", "BOCollections/1.0 (contact@bocollections.local)")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean("discogsClient")
    public RestClient discogsClient() {
        return RestClient.builder()
                .baseUrl("https://api.discogs.com")
                .defaultHeader("User-Agent", "BOCollections/1.0")
                .build();
    }

    @Bean("tmdbClient")
    public RestClient tmdbClient() {
        return RestClient.builder()
                .baseUrl("https://api.themoviedb.org/3")
                .build();
    }

    @Bean("upcItemDbClient")
    public RestClient upcItemDbClient() {
        return RestClient.builder()
                .baseUrl("https://api.upcitemdb.com/prod/trial")
                .build();
    }

    @Bean("ebayClient")
    public RestClient ebayClient() {
        return RestClient.builder()
                .baseUrl("https://api.ebay.com")
                .build();
    }

    @Bean("igdbAuthClient")
    public RestClient igdbAuthClient() {
        return RestClient.builder()
                .baseUrl("https://id.twitch.tv")
                .build();
    }

    @Bean("igdbClient")
    public RestClient igdbClient() {
        return RestClient.builder()
                .baseUrl("https://api.igdb.com/v4")
                .build();
    }

    @Bean("theGamesDbClient")
    public RestClient theGamesDbClient() {
        return RestClient.builder()
                .baseUrl("https://api.thegamesdb.net")
                .build();
    }
}
