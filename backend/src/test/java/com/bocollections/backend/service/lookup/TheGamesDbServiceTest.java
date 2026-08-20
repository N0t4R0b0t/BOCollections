package com.bocollections.backend.service.lookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TheGamesDbServiceTest {

    @Mock private RestClient client;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private TheGamesDbService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lenient().when(client.get()).thenReturn(requestHeadersUriSpec);
        lenient().when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        service = new TheGamesDbService(client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
    }

    @Test
    void searchBoxArt_returnsFrontImagesBeforeBack() {
        String json = """
                {"data":{"games":[{"id":53,"game_title":"Sonic the Hedgehog"}]},
                 "include":{"boxart":{
                     "base_url":{"large":"https://cdn.thegamesdb.net/images/large/"},
                     "data":{"53":[
                         {"id":1,"type":"boxart","side":"back","filename":"boxart/back/53-2.jpg"},
                         {"id":2,"type":"boxart","side":"front","filename":"boxart/front/53-1.jpg"}
                     ]}}}}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        List<String> images = service.searchBoxArt("Sonic the Hedgehog");

        assertThat(images).containsExactly(
                "https://cdn.thegamesdb.net/images/large/boxart/front/53-1.jpg",
                "https://cdn.thegamesdb.net/images/large/boxart/back/53-2.jpg");
    }

    @Test
    void searchBoxArt_notConfigured_returnsEmptyWithoutCallingClient() {
        ReflectionTestUtils.setField(service, "apiKey", "");

        List<String> images = service.searchBoxArt("Sonic the Hedgehog");

        assertThat(images).isEmpty();
    }

    @Test
    void searchBoxArt_noGamesFound_returnsEmpty() {
        when(responseSpec.body(String.class)).thenReturn("{\"data\":{\"games\":[]}}");

        List<String> images = service.searchBoxArt("Nonexistent Game");

        assertThat(images).isEmpty();
    }
}
