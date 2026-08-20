package com.bocollections.backend.service;

import com.bocollections.backend.config.VisionProperties;
import com.bocollections.backend.config.VisionProvider;
import com.bocollections.backend.dto.ExtractResponse;
import com.bocollections.backend.entity.MediaCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for VisualScanService.parseExtractResponse — the "extra details" JSON assembly in
 * particular, since a wrong key or a swallowed exception there would silently drop everything
 * vision reads off the box beyond the core fields.
 */
class VisualScanServiceTest {

    private VisualScanService service;

    @BeforeEach
    void setUp() {
        service = new VisualScanService(new VisionProperties(), new ObjectMapper());
    }

    @Test
    void parseExtractResponse_coreFieldsParsedAsBefore() {
        String raw = """
                {"category":"VIDEO","format":"Blu-ray","title":"Dredd","subtitle":null,
                 "publisher":"Lionsgate","releaseYear":2012,"barcode":null,
                 "description":"A future cop.","confidence":"HIGH","notes":"Steelbook case"}
                """;

        ExtractResponse result = service.parseExtractResponse(raw);

        assertThat(result.getCategory()).isEqualTo(MediaCategory.VIDEO);
        assertThat(result.getFormat()).isEqualTo("Blu-ray");
        assertThat(result.getTitle()).isEqualTo("Dredd");
        assertThat(result.getPublisher()).isEqualTo("Lionsgate");
        assertThat(result.getReleaseYear()).isEqualTo(2012);
        assertThat(result.getConfidence()).isEqualTo("HIGH");
        assertThat(result.getNotes()).isEqualTo("Steelbook case");
    }

    @Test
    void parseExtractResponse_buildsExtraMetadataFromEverythingElse() {
        String raw = """
                {"category":"VIDEO","title":"Dredd","confidence":"HIGH",
                 "edition":"Director's Cut","discCount":2,"dualSided":false,
                 "runtimeMinutes":96,"regionCode":"Region A",
                 "cast":["Karl Urban","Olivia Thirlby"],"specialFeatures":["Commentary","Deleted scenes"]}
                """;

        ExtractResponse result = service.parseExtractResponse(raw);

        assertThat(result.getMetadata())
                .contains("\"edition\":\"Director's Cut\"")
                .contains("\"discCount\":2")
                .contains("\"dualSided\":false")
                .contains("\"runtimeMinutes\":96")
                .contains("\"regionCode\":\"Region A\"")
                .contains("\"Karl Urban\"")
                .contains("\"Deleted scenes\"");
    }

    @Test
    void parseExtractResponse_noExtraFieldsPresent_metadataIsNull() {
        String raw = "{\"category\":\"VIDEO\",\"title\":\"Dredd\",\"confidence\":\"HIGH\"}";

        ExtractResponse result = service.parseExtractResponse(raw);

        assertThat(result.getMetadata()).isNull();
    }

    @Test
    void parseExtractResponse_nullExtraFields_areOmittedNotFabricated() {
        String raw = """
                {"category":"VIDEO","title":"Dredd","confidence":"HIGH",
                 "edition":null,"discCount":null,"cast":null,"platform":null}
                """;

        ExtractResponse result = service.parseExtractResponse(raw);

        assertThat(result.getMetadata()).isNull();
    }

    @Test
    void parseExtractResponse_toleratesProseWrappedJson() {
        String raw = "Sure! Here's the JSON: {\"category\":\"PRINT\",\"title\":\"Dune\",\"confidence\":\"MEDIUM\",\"pageCount\":412} Hope that helps!";

        ExtractResponse result = service.parseExtractResponse(raw);

        assertThat(result.getTitle()).isEqualTo("Dune");
        assertThat(result.getMetadata()).contains("\"pageCount\":412");
    }

    @Test
    void parseExtractResponse_malformedJson_fallsBackGracefully() {
        ExtractResponse result = service.parseExtractResponse("not json at all");

        assertThat(result.getConfidence()).isEqualTo("LOW");
        assertThat(result.getNotes()).isNotBlank();
        assertThat(result.getMetadata()).isNull();
    }

    private static VisualScanService.ResolvedEndpoint endpoint(String name, boolean primary) {
        var config = new VisionProperties.Endpoint();
        config.setName(name);
        config.setPrimary(primary);
        return new VisualScanService.ResolvedEndpoint(name, null, config);
    }

    @Test
    void orderByPrimaryFirst_movesThePrimaryEndpointToTheFront() {
        var backup = endpoint("backup", false);
        var gemini = endpoint("gemini", true);
        var ollama = endpoint("ollama", false);

        List<VisualScanService.ResolvedEndpoint> ordered =
                VisualScanService.orderByPrimaryFirst(List.of(backup, gemini, ollama));

        assertThat(ordered).extracting(VisualScanService.ResolvedEndpoint::name)
                .containsExactly("gemini", "backup", "ollama");
    }

    @Test
    void orderByPrimaryFirst_noPrimaryMarked_preservesListOrder() {
        var first = endpoint("first", false);
        var second = endpoint("second", false);

        List<VisualScanService.ResolvedEndpoint> ordered =
                VisualScanService.orderByPrimaryFirst(List.of(first, second));

        assertThat(ordered).extracting(VisualScanService.ResolvedEndpoint::name)
                .containsExactly("first", "second");
    }

    @Test
    void buildOptions_ollama_setsModelAndExpandedContext() {
        var options = (OllamaChatOptions) VisualScanService.buildOptions(VisionProvider.OLLAMA, "qwen2.5vl:7b");

        assertThat(options.getModel()).isEqualTo("qwen2.5vl:7b");
        assertThat(options.getNumCtx()).isEqualTo(8192);
    }

    @Test
    void buildOptions_gemini_setsModel() {
        var options = (GoogleGenAiChatOptions) VisualScanService.buildOptions(VisionProvider.GEMINI, "gemini-2.0-flash");

        assertThat(options.getModel()).isEqualTo("gemini-2.0-flash");
    }
}
