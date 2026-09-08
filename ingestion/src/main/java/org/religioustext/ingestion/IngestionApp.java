package org.religioustext.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Religious Texts Platform — Ingestion pipeline entry point.
 * Runs as a separate Spring Boot application from the Vaadin app.
 * Triggered manually or via scheduled jobs.
 */
@SpringBootApplication
@EnableScheduling
public class IngestionApp {

    public static void main(final String[] theArgs) {
        SpringApplication.run(IngestionApp.class, theArgs);
    }
}
