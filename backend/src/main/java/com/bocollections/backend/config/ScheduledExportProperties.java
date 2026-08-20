package com.bocollections.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** See ScheduledCollectionExportTask — off by default (EXPORT_SCHEDULE_ENABLED / enabled), a
 * background JSON backup of every collection written to disk on an interval. */
@Component
@ConfigurationProperties(prefix = "app.export.scheduled")
@Data
public class ScheduledExportProperties {
    private boolean enabled = false;
    private long intervalMs = 86_400_000;
    private String directory = "./exports";
}
