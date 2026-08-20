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
class EbayServiceTest {

    @Mock private RestClient client;
    @Mock private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec tokenResponseSpec;
    @Mock private RestClient.RequestHeadersUriSpec getUriSpec;
    @Mock private RestClient.RequestHeadersSpec getHeadersSpec;
    @Mock private RestClient.ResponseSpec searchResponseSpec;

    private EbayService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lenient().when(client.post()).thenReturn(postUriSpec);
        lenient().when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        lenient().when(postBodySpec.header(anyString(), anyString())).thenReturn(postBodySpec);
        lenient().when(postBodySpec.contentType(any())).thenReturn(postBodySpec);
        lenient().when(postBodySpec.body(anyString())).thenReturn(postBodySpec);
        lenient().when(postBodySpec.retrieve()).thenReturn(tokenResponseSpec);
        lenient().when(tokenResponseSpec.body(String.class)).thenReturn("{\"access_token\":\"tok\",\"expires_in\":7200}");

        lenient().when(client.get()).thenReturn(getUriSpec);
        lenient().when(getUriSpec.uri(anyString(), any(Object[].class))).thenReturn(getHeadersSpec);
        lenient().when(getHeadersSpec.header(anyString(), anyString())).thenReturn(getHeadersSpec);
        lenient().when(getHeadersSpec.retrieve()).thenReturn(searchResponseSpec);

        service = new EbayService(client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "clientId", "test-client");
        ReflectionTestUtils.setField(service, "clientSecret", "test-secret");
    }

    @Test
    void lookupImages_collectsPrimaryAndAdditionalImagesAcrossListings() {
        String json = """
                {"itemSummaries":[
                    {"image":{"imageUrl":"https://ebay/1-primary.jpg"},
                     "additionalImages":[{"imageUrl":"https://ebay/1-back.jpg"},{"imageUrl":"https://ebay/1-disc.jpg"}]},
                    {"image":{"imageUrl":"https://ebay/2-primary.jpg"}}
                ]}
                """;
        when(searchResponseSpec.body(String.class)).thenReturn(json);

        List<String> images = service.lookupImages("024543008194");

        assertThat(images).containsExactly(
                "https://ebay/1-primary.jpg", "https://ebay/1-back.jpg", "https://ebay/1-disc.jpg", "https://ebay/2-primary.jpg");
    }

    @Test
    void lookupImages_notConfigured_returnsEmptyWithoutCallingClient() {
        ReflectionTestUtils.setField(service, "clientId", "");

        List<String> images = service.lookupImages("024543008194");

        assertThat(images).isEmpty();
    }
}
