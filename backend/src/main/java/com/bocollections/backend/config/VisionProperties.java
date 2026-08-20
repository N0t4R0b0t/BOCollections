package com.bocollections.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Vision endpoints, tried in order — first one that responds wins. Each endpoint may be an
 * Ollama box or a Gemini API key (see {@code provider}); mixing both lets one act as a
 * failover for the other. Endpoints may only have some vision models pulled/available, so
 * per-task overrides let one endpoint use a different model than another for the same task.
 * Marking one endpoint {@code primary: true} moves it to the front of the failover order
 * regardless of its position in the list; with none marked, list order wins as before. When
 * `endpoints` is empty, VisualScanService falls back to a single legacy Ollama endpoint built
 * from spring.ai.ollama.base-url + app.vision.*-model, so existing single-box setups keep
 * working unchanged.
 */
@Component
@ConfigurationProperties(prefix = "app.vision")
@Data
public class VisionProperties {

    private List<Endpoint> endpoints = List.of();

    @Data
    public static class Endpoint {
        private String name;
        private VisionProvider provider = VisionProvider.OLLAMA;
        private boolean primary = false;
        private String baseUrl;
        private String apiKey;
        private String model;
        private String verifyModel;
        private String extractModel;
        private String shelfModel;

        public String modelFor(VisionTask task) {
            String override = switch (task) {
                case VERIFY -> verifyModel;
                case EXTRACT -> extractModel;
                case SHELF -> shelfModel;
            };
            return override != null ? override : model;
        }
    }
}
