package com.bocollections.backend.service.lookup;

import com.bocollections.backend.dto.LookupResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IgdbServiceTest {

    @Mock private RestClient authClient;
    @Mock private RestClient client;
    @Mock private RestClient.RequestBodyUriSpec authUriSpec;
    @Mock private RestClient.RequestBodySpec authHeadersSpec;
    @Mock private RestClient.ResponseSpec authResponseSpec;
    @Mock private RestClient.RequestBodyUriSpec bodyUriSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private IgdbService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lenient().when(authClient.post()).thenReturn(authUriSpec);
        lenient().when(authUriSpec.uri(anyString(), any(Object[].class))).thenReturn(authHeadersSpec);
        lenient().when(authHeadersSpec.retrieve()).thenReturn(authResponseSpec);
        lenient().when(authResponseSpec.body(String.class)).thenReturn("{\"access_token\":\"tok\",\"expires_in\":5587808}");

        lenient().when(client.post()).thenReturn(bodyUriSpec);
        lenient().when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
        lenient().when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        lenient().when(bodySpec.body(anyString())).thenReturn(bodySpec);
        lenient().when(bodySpec.retrieve()).thenReturn(responseSpec);

        service = new IgdbService(authClient, client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "clientId", "test-client");
        ReflectionTestUtils.setField(service, "clientSecret", "test-secret");
    }

    @Test
    void searchByTitle_parsesNameCoverAndReleaseYear() {
        String json = """
                [{"id":1942,"name":"Sonic the Hedgehog","first_release_date":647568000,
                  "cover":{"image_id":"co1r7f"}}]
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<LookupResult> result = service.searchByTitle("Sonic the Hedgehog");

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo("Sonic the Hedgehog");
        assertThat(result.get().getExternalId()).isEqualTo("1942");
        assertThat(result.get().getReleaseYear()).isEqualTo(1990);
        assertThat(result.get().getCoverUrl()).isEqualTo("https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1r7f.jpg");
    }

    @Test
    void searchByTitle_emptyResults_returnsEmpty() {
        when(responseSpec.body(String.class)).thenReturn("[]");

        Optional<LookupResult> result = service.searchByTitle("Nonexistent Game");

        assertThat(result).isEmpty();
    }

    @Test
    void isConfigured_falseWhenCredentialsMissing() {
        ReflectionTestUtils.setField(service, "clientId", "");
        assertThat(service.isConfigured()).isFalse();
    }
}
