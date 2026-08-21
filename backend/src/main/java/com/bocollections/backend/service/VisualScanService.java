package com.bocollections.backend.service;

import com.bocollections.backend.config.VisionProperties;
import com.bocollections.backend.config.VisionProvider;
import com.bocollections.backend.config.VisionTask;
import com.bocollections.backend.dto.*;
import com.bocollections.backend.entity.MediaCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisualScanService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final VisionProperties visionProperties;
    private final ObjectMapper objectMapper;

    // Legacy single-endpoint fallback, used only when app.vision.endpoints is unset — keeps
    // existing single-box deployments working without requiring the new list config.
    @Value("${spring.ai.ollama.base-url}")
    private String legacyBaseUrl;

    @Value("${app.vision.model}")
    private String legacyModel;

    @Value("${app.vision.verify-model}")
    private String legacyVerifyModel;

    @Value("${app.vision.extract-model}")
    private String legacyExtractModel;

    @Value("${app.vision.shelf-model}")
    private String legacyShelfModel;

    private List<ResolvedEndpoint> resolvedEndpoints;

    record ResolvedEndpoint(String name, ChatModel chatModel, VisionProperties.Endpoint config) {}

    @PostConstruct
    void initEndpoints() {
        List<VisionProperties.Endpoint> configured = visionProperties.getEndpoints();
        if (configured.isEmpty()) {
            VisionProperties.Endpoint legacy = new VisionProperties.Endpoint();
            legacy.setName("default");
            legacy.setBaseUrl(legacyBaseUrl);
            legacy.setModel(legacyModel);
            legacy.setVerifyModel(legacyVerifyModel);
            legacy.setExtractModel(legacyExtractModel);
            legacy.setShelfModel(legacyShelfModel);
            configured = List.of(legacy);
        }
        List<ResolvedEndpoint> resolved = configured.stream()
                .map(e -> new ResolvedEndpoint(
                        e.getName() != null ? e.getName() : e.getBaseUrl(),
                        buildChatModel(e),
                        e))
                .toList();
        resolvedEndpoints = orderByPrimaryFirst(resolved);
        log.info("Vision endpoints configured: {}", resolvedEndpoints.stream().map(ResolvedEndpoint::name).toList());
    }

    /** Stable-sorts so any endpoint(s) marked {@code primary} come first; list order wins otherwise. */
    static List<ResolvedEndpoint> orderByPrimaryFirst(List<ResolvedEndpoint> endpoints) {
        return endpoints.stream()
                .sorted(Comparator.comparing(e -> !e.config().isPrimary()))
                .toList();
    }

    private ChatModel buildChatModel(VisionProperties.Endpoint endpoint) {
        return switch (endpoint.getProvider()) {
            case OLLAMA -> buildOllamaChatModel(endpoint.getBaseUrl());
            case GEMINI -> buildGeminiChatModel(endpoint.getApiKey(), endpoint.getModel());
        };
    }

    private ChatModel buildOllamaChatModel(String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        var ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        return OllamaChatModel.builder().ollamaApi(ollamaApi).build();
    }

    private ChatModel buildGeminiChatModel(String apiKey, String defaultModel) {
        var genAiClient = Client.builder().apiKey(apiKey).build();
        var defaultOptions = GoogleGenAiChatOptions.builder().model(defaultModel).build();
        return GoogleGenAiChatModel.builder().genAiClient(genAiClient).defaultOptions(defaultOptions).build();
    }

    /**
     * Asks the vision model whether the camera frame matches the metadata found by barcode lookup.
     */
    public ScanVerifyResponse verify(ScanVerifyRequest req) {
        try {
            LookupResult item = req.getLookupResult();
            byte[] imageBytes = Base64.getDecoder().decode(req.getImageBase64());
            MimeType mime = parseMime(req.getImageMimeType());

            String prompt = """
                    You are helping verify a physical media item in a collection app.
                    The barcode scanner found: "%s" by "%s" (format: %s, year: %s).
                    Look at this image and determine if it shows that exact item.
                    Respond ONLY with valid JSON, nothing else:
                    {"matches": true/false, "confidence": "HIGH" or "MEDIUM" or "LOW", "reason": "one short sentence"}
                    """.formatted(
                    item.getTitle(),
                    item.getPublisher() != null ? item.getPublisher() : "unknown",
                    item.getFormat() != null ? item.getFormat() : "unknown",
                    item.getReleaseYear() != null ? item.getReleaseYear() : "unknown"
            );

            String raw = callVisionModel(prompt, List.of(new Media(mime, new ByteArrayResource(imageBytes))), VisionTask.VERIFY);
            return parseVerifyResponse(raw);

        } catch (VisionUnavailableException e) {
            log.warn("Visual verification unavailable: {}", e.getMessage());
            return ScanVerifyResponse.builder()
                    .matches(null)
                    .confidence("LOW")
                    .reason("AI vision service is currently unreachable — please confirm manually")
                    .visionAvailable(false)
                    .build();
        } catch (Exception e) {
            log.warn("Visual verification failed: {}", e.getMessage());
            return ScanVerifyResponse.builder()
                    .matches(null)
                    .confidence("LOW")
                    .reason("Vision check unavailable — please confirm manually")
                    .build();
        }
    }

    /**
     * Analyses one or more captures of an unknown item and extracts structured metadata.
     */
    public ExtractResponse extract(ExtractRequest req) {
        try {
            MimeType mime = parseMime(req.getImageMimeType());
            List<Media> mediaList = new ArrayList<>();
            for (String b64 : req.getImagesBase64()) {
                byte[] bytes = Base64.getDecoder().decode(b64);
                mediaList.add(new Media(mime, new ByteArrayResource(bytes)));
            }

            String hint = req.getHint() != null ? " The user says: \"" + req.getHint() + "\"." : "";
            // Deliberately asks for far more than the core fields need — the goal is to capture
            // everything that actually distinguishes *this specific physical release* from every
            // other release of the same content (edition, region, disc count, pressing details,
            // special features, etc.), both because collectors care and because it's exactly the
            // kind of data worth eventually contributing back to public barcode/release databases,
            // which barcode lookups alone can never supply (they only know the barcode they were
            // given, never what's printed on the box). Every field must be null (never guessed)
            // when it isn't clearly visible — a small vision model fabricating a plausible-looking
            // value is worse than it saying nothing (see the barcode field: never trusted as
            // authoritative downstream precisely because of this failure mode).
            String prompt = """
                    You are helping catalogue a physical media item (book, magazine, CD, vinyl record, DVD, video game, etc.).%s
                    Examine all provided images carefully — front cover, back cover, spine, disc/label, credits block —
                    and extract as much as you can actually read. Use null for anything not clearly visible; never guess.
                    Respond ONLY with valid JSON, nothing else:
                    {
                      "category": "PRINT" or "AUDIO" or "VIDEO" or "GAME" or "OTHER",
                      "format": e.g. "Book", "Vinyl LP", "CD", "DVD", "Game Cartridge",
                      "title": "exact title",
                      "subtitle": "subtitle or null",
                      "publisher": "publisher, label, or studio name",
                      "releaseYear": integer or null,
                      "barcode": "if visible on the item, else null",
                      "description": "brief one-sentence description",
                      "confidence": "HIGH" or "MEDIUM" or "LOW",
                      "notes": "anything you noticed that might help the user (e.g. special edition, condition)",
                      "edition": "e.g. \\"Director's Cut\\", \\"Collector's Edition\\", \\"First Edition\\", or null",
                      "language": "primary language of the content, or null",
                      "countryOfRelease": "e.g. USA, UK, Japan — from packaging/language/region markers, or null",
                      "limitedEdition": "e.g. \\"Numbered, 500 of 1000\\", or null",
                      "discCount": integer or null (check the back cover/spine, e.g. \\"2-Disc Special Edition\\"),
                      "dualSided": true, false, or null (only relevant if discCount is 1 — printed on both sides?),
                      "runtimeMinutes": integer or null (VIDEO — usually printed on the back cover),
                      "contentRating": "content rating e.g. PG-13, R, ESRB T, or null",
                      "aspectRatio": "e.g. \\"16:9 Widescreen\\", or null (VIDEO)",
                      "audioLanguages": ["array of audio languages listed"] or null (VIDEO),
                      "subtitleLanguages": ["array of subtitle languages listed"] or null (VIDEO),
                      "specialFeatures": ["array of bonus features/extras listed on the box"] or null (VIDEO),
                      "regionCode": "e.g. \\"Region 1\\", \\"Region A\\", or null (VIDEO)",
                      "director": "if credited on the box (VIDEO)",
                      "cast": ["array of cast names printed on the box"] or null (VIDEO),
                      "artist": "if credited (AUDIO)",
                      "catalogNumber": "label/catalog or studio SKU number printed on the spine, label, or near the barcode — a separate printed number, not the barcode itself (AUDIO/VIDEO)",
                      "speed": "e.g. \\"33 RPM\\", \\"45 RPM\\" (AUDIO)",
                      "vinylColor": "e.g. \\"Black\\", \\"Clear\\", \\"Red\\" (AUDIO)",
                      "vinylWeight": "e.g. \\"180g\\" (AUDIO)",
                      "gatefold": true, false, or null (AUDIO),
                      "tracklist": ["array of track titles, if listed"] or null (AUDIO),
                      "isbn": "if printed and different from the barcode (PRINT)",
                      "printing": "e.g. \\"First Edition, First Printing\\" (PRINT)",
                      "pageCount": integer or null (PRINT),
                      "authors": ["array of author names"] or null (PRINT),
                      "illustrator": "if credited separately from the author (PRINT)",
                      "series": "if part of a series (PRINT)",
                      "platform": "e.g. \\"PlayStation 2\\", \\"Nintendo Switch\\" (GAME)",
                      "developer": "if distinct from the publisher (GAME)",
                      "players": "e.g. \\"1-4 players\\" (GAME)"
                    }
                    """.formatted(hint);

            String raw = callVisionModel(prompt, mediaList, VisionTask.EXTRACT);
            return parseExtractResponse(raw);

        } catch (VisionUnavailableException e) {
            log.warn("Visual extraction unavailable: {}", e.getMessage());
            return ExtractResponse.builder()
                    .confidence("LOW")
                    .notes("AI vision service is currently unreachable — please fill in the details manually")
                    .visionAvailable(false)
                    .build();
        } catch (Exception e) {
            log.warn("Visual extraction failed: {}", e.getMessage());
            return ExtractResponse.builder()
                    .confidence("LOW")
                    .notes("Extraction failed — please fill in the details manually")
                    .build();
        }
    }

    /**
     * Shared by every vision call in this app (verify/extract here, and ThriftService's shelf
     * scan) — one place that builds the message and calls Ollama. Tries each configured endpoint
     * in order (each with its own connect/read timeout so a hung box can't block failover) and
     * returns the first success; throws VisionUnavailableException only if all of them failed.
     */
    String callVisionModel(String prompt, List<Media> mediaList, VisionTask task) {
        var message = UserMessage.builder()
                .text(prompt)
                .media(mediaList)
                .build();

        StringBuilder failures = new StringBuilder();
        for (ResolvedEndpoint endpoint : resolvedEndpoints) {
            String model = endpoint.config().modelFor(task);
            try {
                var response = endpoint.chatModel().call(
                        new Prompt(List.of(message), buildOptions(endpoint.config().getProvider(), model)));
                return response.getResult().getOutput().getText();
            } catch (Exception e) {
                // e.getMessage() alone is often a useless top-level wrapper (Spring AI's Google
                // GenAI client throws a generic "Failed to generate content" with the actual
                // reason — bad API key, quota, safety filter, oversized payload — buried in the
                // cause chain, never logged anywhere). Log the full exception for the stack
                // trace, and surface the deepest cause's message in both the log line and the
                // failures string so a real diagnosis doesn't require reproducing the failure.
                String rootCause = rootCauseMessage(e);
                log.warn("Vision call to endpoint '{}' (model {}) failed for {} task: {} (root cause: {})",
                        endpoint.name(), model, task, e.getMessage(), rootCause, e);
                failures.append(endpoint.name()).append(": ").append(rootCause).append("; ");
            }
        }
        throw new VisionUnavailableException("All vision endpoints failed — " + failures);
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    static ChatOptions buildOptions(VisionProvider provider, String model) {
        return switch (provider) {
            case OLLAMA -> OllamaChatOptions.builder().model(model)
                    // Ollama's default context is 4096 tokens — a multi-image guided capture
                    // batch can exceed that easily even with the frontend downscaling images
                    // before upload. Doubling gives real headroom without the KV-cache cost of
                    // going much higher.
                    .numCtx(8192)
                    .build();
            case GEMINI -> GoogleGenAiChatOptions.builder().model(model).build();
        };
    }

    private ScanVerifyResponse parseVerifyResponse(String raw) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            return ScanVerifyResponse.builder()
                    .matches(node.path("matches").asBoolean())
                    .confidence(node.path("confidence").asText("LOW"))
                    .reason(node.path("reason").asText(""))
                    .build();
        } catch (Exception e) {
            log.debug("Could not parse verify response: {}", raw);
            return ScanVerifyResponse.builder()
                    .matches(null)
                    .confidence("LOW")
                    .reason("Could not interpret vision response")
                    .build();
        }
    }

    ExtractResponse parseExtractResponse(String raw) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);

            MediaCategory category = null;
            try { category = MediaCategory.valueOf(node.path("category").asText("")); }
            catch (IllegalArgumentException ignored) {}

            Integer year = null;
            JsonNode yearNode = node.get("releaseYear");
            if (yearNode != null && !yearNode.isNull()) {
                try { year = yearNode.asInt(); } catch (Exception ignored) {}
            }

            return ExtractResponse.builder()
                    .category(category)
                    .format(node.path("format").asText(null))
                    .title(nullIfEmpty(node.path("title").asText(null)))
                    .subtitle(nullIfEmpty(node.path("subtitle").asText(null)))
                    .publisher(nullIfEmpty(node.path("publisher").asText(null)))
                    .releaseYear(year)
                    .barcode(nullIfEmpty(node.path("barcode").asText(null)))
                    .description(nullIfEmpty(node.path("description").asText(null)))
                    .confidence(node.path("confidence").asText("LOW"))
                    .notes(nullIfEmpty(node.path("notes").asText(null)))
                    .metadata(buildExtraMetadata(node))
                    .build();
        } catch (Exception e) {
            log.debug("Could not parse extract response: {}", raw);
            return ExtractResponse.builder()
                    .confidence("LOW")
                    .notes("Could not interpret vision response — please fill in manually")
                    .build();
        }
    }

    private static final String[] EXTRA_STRING_FIELDS = {
            "edition", "language", "countryOfRelease", "limitedEdition", "contentRating", "aspectRatio",
            "regionCode", "director", "artist", "catalogNumber", "speed", "vinylColor", "vinylWeight",
            "isbn", "printing", "illustrator", "series", "platform", "developer", "players",
    };
    private static final String[] EXTRA_INT_FIELDS = {"discCount", "runtimeMinutes", "pageCount"};
    private static final String[] EXTRA_BOOL_FIELDS = {"dualSided", "gatefold"};
    private static final String[] EXTRA_ARRAY_FIELDS = {
            "audioLanguages", "subtitleLanguages", "specialFeatures", "cast", "tracklist", "authors",
    };

    /** Everything the extraction prompt asks for beyond the core fields — see the prompt in
     * extract() for what each key means. Whatever the model actually filled in (skipping nulls)
     * becomes the "extra details" JSON, same shape/keys as the barcode-side sources so it merges
     * and displays through the same pipeline (see frontend mergeFindings + extraMetadata.ts). */
    private String buildExtraMetadata(JsonNode node) {
        var extra = objectMapper.createObjectNode();
        for (String key : EXTRA_STRING_FIELDS) {
            String value = nullIfEmpty(node.path(key).asText(null));
            if (value != null) extra.put(key, value);
        }
        for (String key : EXTRA_INT_FIELDS) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                try { extra.put(key, value.asInt()); } catch (Exception ignored) {}
            }
        }
        for (String key : EXTRA_BOOL_FIELDS) {
            JsonNode value = node.get(key);
            if (value != null && value.isBoolean()) extra.put(key, value.asBoolean());
        }
        for (String key : EXTRA_ARRAY_FIELDS) {
            JsonNode value = node.get(key);
            if (value != null && value.isArray() && !value.isEmpty()) extra.set(key, value);
        }
        try {
            return extra.isEmpty() ? null : objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Vision models frequently wrap JSON in prose ("Sure! Here is the JSON: {...}").
     * Extracting the first balanced brace pair is more robust than asking the model
     * for "raw JSON only" — small models often ignore that instruction.
     */
    String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    MimeType parseMime(String mime) {
        try { return MimeTypeUtils.parseMimeType(mime != null ? mime : "image/jpeg"); }
        catch (Exception e) { return MimeTypeUtils.IMAGE_JPEG; }
    }

    private String nullIfEmpty(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }
}
