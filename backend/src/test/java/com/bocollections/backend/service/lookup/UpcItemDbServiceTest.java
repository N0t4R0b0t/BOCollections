package com.bocollections.backend.service.lookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpcItemDbServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private UpcItemDbService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        service = new UpcItemDbService(restClient, new ObjectMapper());
    }

    @Test
    void lookup_returnsTitleFromFirstItem() {
        String json = """
                {"code":"OK","total":1,"offset":0,"items":[{"title":"Big Momma's House (Special Edition)"}]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<UpcItemDbService.UpcItemDbResult> result = service.lookup("024543008194");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Big Momma's House (Special Edition)");
        assertThat(result.get().brand()).isNull();
    }

    @Test
    void lookup_capturesBrandAsDistributor() {
        String json = """
                {"code":"OK","total":1,"items":[{"title":"Coyote Ugly (DVD)","brand":"Mill Creek"}]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<UpcItemDbService.UpcItemDbResult> result = service.lookup("786936144505");

        assertThat(result).isPresent();
        assertThat(result.get().brand()).isEqualTo("Mill Creek");
    }

    @Test
    void lookup_capturesImages() {
        String json = """
                {"code":"OK","total":1,"items":[{"title":"Dredd (Blu-ray )","images":["https://example.com/a.jpg","https://example.com/b.jpg"]}]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<UpcItemDbService.UpcItemDbResult> result = service.lookup("786936144505");

        assertThat(result).isPresent();
        assertThat(result.get().images()).containsExactly("https://example.com/a.jpg", "https://example.com/b.jpg");
    }

    @Test
    void lookup_noImages_returnsEmptyList() {
        String json = """
                {"code":"OK","total":1,"items":[{"title":"Coyote Ugly (DVD)"}]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<UpcItemDbService.UpcItemDbResult> result = service.lookup("786936144505");

        assertThat(result).isPresent();
        assertThat(result.get().images()).isEmpty();
    }

    @Test
    void lookup_emptyItems_returnsEmpty() {
        when(responseSpec.body(String.class)).thenReturn("{\"code\":\"OK\",\"total\":0,\"items\":[]}");

        assertThat(service.lookup("999999999999")).isEmpty();
    }

    @Test
    void lookup_malformedResponse_isCaughtAndReturnsEmpty() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("boom"));

        assertThat(service.lookup("024543008194")).isEmpty();
    }

    // Real data from a live UPCitemdb lookup — TMDB's title search returns zero results against
    // the raw string but matches cleanly once stripped down to "Coyote Ugly", confirmed directly
    // against the TMDB API. This is what silently broke every video-barcode lookup end-to-end.
    @Test
    void lookup_stripsFormatTagAndDistributorGenreSuffix() {
        when(responseSpec.body(String.class)).thenReturn(
                "{\"code\":\"OK\",\"total\":1,\"items\":[{\"title\":\"Coyote Ugly (DVD)  Mill Creek  Comedy\"}]}");

        assertThat(service.lookup("786936144505").get().title()).isEqualTo("Coyote Ugly");
    }

    @Test
    void lookup_stripsFormatTagWithTrailingSpaceInsideParens() {
        when(responseSpec.body(String.class)).thenReturn(
                "{\"code\":\"OK\",\"total\":1,\"items\":[{\"title\":\"Dredd (Blu-ray )\"}]}");

        assertThat(service.lookup("031398163763").get().title()).isEqualTo("Dredd");
    }

    // Regression test: TMDB has no way to know which physical disc a barcode is for, and used to
    // be hardcoded to "DVD" everywhere — silently mislabeling every Blu-ray. This tag is the only
    // place in the whole lookup chain that actually carries that information.
    @Test
    void lookup_capturesBluRayFormatTag() {
        when(responseSpec.body(String.class)).thenReturn(
                "{\"code\":\"OK\",\"total\":1,\"items\":[{\"title\":\"Dredd (Blu-ray )\"}]}");

        assertThat(service.lookup("031398163763").get().format()).isEqualTo("Blu-ray");
    }

    @Test
    void lookup_capturesDvdFormatTagWithDistributorSuffix() {
        when(responseSpec.body(String.class)).thenReturn(
                "{\"code\":\"OK\",\"total\":1,\"items\":[{\"title\":\"Coyote Ugly (DVD)  Mill Creek  Comedy\"}]}");

        assertThat(service.lookup("786936144505").get().format()).isEqualTo("DVD");
    }

    @Test
    void lookup_noRecognizableFormatTag_returnsNullFormat() {
        when(responseSpec.body(String.class)).thenReturn(
                "{\"code\":\"OK\",\"total\":1,\"items\":[{\"title\":\"Big Momma's House (Special Edition)\"}]}");

        assertThat(service.lookup("024543008194").get().format()).isNull();
    }
}
