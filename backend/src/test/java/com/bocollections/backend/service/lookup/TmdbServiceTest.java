package com.bocollections.backend.service.lookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmdbServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private TmdbService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        service = new TmdbService(restClient, new ObjectMapper());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
    }

    // Real-world case that motivated this: a barcode for "Dredd" (2012) was matching "Judge
    // Dredd" (1995) because TMDB's own /search/multi ordering favors popularity, and the 1995
    // film has far more votes despite being the looser title match.
    @Test
    void searchByTitle_prefersExactTitleMatchOverMorePopularPartialMatch() {
        String json = """
                {"results":[
                    {"id":1,"media_type":"movie","title":"Judge Dredd","release_date":"1995-06-30","popularity":40.0},
                    {"id":2,"media_type":"movie","title":"Dredd","release_date":"2012-09-21","popularity":20.0}
                ]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<com.bocollections.backend.dto.LookupResult> result = service.searchByTitle("Dredd");

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo("Dredd");
        assertThat(result.get().getExternalId()).isEqualTo("2");
        assertThat(result.get().getReleaseYear()).isEqualTo(2012);
    }

    @Test
    void searchByTitle_noExactMatch_fallsBackToMostPopular() {
        String json = """
                {"results":[
                    {"id":1,"media_type":"movie","title":"Se7en","release_date":"1995-09-22","popularity":40.0},
                    {"id":2,"media_type":"movie","title":"Seven Pounds","release_date":"2008-12-19","popularity":10.0}
                ]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<com.bocollections.backend.dto.LookupResult> result = service.searchByTitle("Seven");

        assertThat(result).isPresent();
        assertThat(result.get().getExternalId()).isEqualTo("1");
    }

    // Regression test for a real incident: a barcode resolving to "Seven" (the 1995 David Fincher
    // film, actually titled "Se7en" on TMDB) kept matching much-more-popular unrelated titles
    // ("A Knight of the Seven Kingdoms", "Seven Snipers") because a strict exact-match check gave
    // "Se7en" no credit at all for being one character off from an exact match.
    @Test
    void searchByTitle_prefersNearExactMatchOverUnrelatedPopularTitles() {
        String json = """
                {"results":[
                    {"id":1,"media_type":"tv","name":"A Knight of the Seven Kingdoms","first_air_date":"2026-01-18","popularity":900.0},
                    {"id":2,"media_type":"movie","title":"Seven Snipers","release_date":"2026-03-01","popularity":45.0},
                    {"id":3,"media_type":"movie","title":"Se7en","release_date":"1995-09-22","popularity":60.0}
                ]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<com.bocollections.backend.dto.LookupResult> result = service.searchByTitle("Seven");

        assertThat(result).isPresent();
        assertThat(result.get().getExternalId()).isEqualTo("3");
        assertThat(result.get().getTitle()).isEqualTo("Se7en");
    }

    @Test
    void searchByTitle_excludingRejectedId_fallsBackToNextBest() {
        String json = """
                {"results":[
                    {"id":1,"media_type":"movie","title":"Judge Dredd","release_date":"1995-06-30","popularity":40.0},
                    {"id":2,"media_type":"movie","title":"Dredd","release_date":"2012-09-21","popularity":20.0}
                ]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<com.bocollections.backend.dto.LookupResult> result = service.searchByTitle("Dredd", Set.of("2"));

        assertThat(result).isPresent();
        assertThat(result.get().getExternalId()).isEqualTo("1");
        assertThat(result.get().getTitle()).isEqualTo("Judge Dredd");
    }

    @Test
    void searchByTitle_everyCandidateExcluded_returnsEmpty() {
        String json = """
                {"results":[
                    {"id":1,"media_type":"movie","title":"Dredd","release_date":"2012-09-21","popularity":20.0}
                ]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        assertThat(service.searchByTitle("Dredd", Set.of("1"))).isEmpty();
    }

    @Test
    void searchByTitle_skipsPersonResults() {
        String json = """
                {"results":[
                    {"id":1,"media_type":"person","name":"Karl Urban","popularity":90.0},
                    {"id":2,"media_type":"movie","title":"Dredd","release_date":"2012-09-21","popularity":20.0}
                ]}
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        Optional<com.bocollections.backend.dto.LookupResult> result = service.searchByTitle("Dredd");

        assertThat(result).isPresent();
        assertThat(result.get().getExternalId()).isEqualTo("2");
    }
}
