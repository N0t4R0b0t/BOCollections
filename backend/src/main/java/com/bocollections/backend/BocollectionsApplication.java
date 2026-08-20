package com.bocollections.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling powers ScheduledCollectionExportTask (app.export.scheduled.* — off by
// default). No other @Scheduled tasks in the app yet.
@SpringBootApplication
@EnableScheduling
public class BocollectionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(BocollectionsApplication.class, args);
    }
}
