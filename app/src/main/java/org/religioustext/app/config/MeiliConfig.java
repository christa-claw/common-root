package org.religioustext.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Meilisearch connection settings for full-text search.
 *
 * The index is a DERIVED artifact, populated out-of-band by reindex_search.py
 * (BaseX + MySQL -> Meili); the application only QUERIES it (see SearchService).
 * Deliberately does NOT declare a RestTemplate bean — it reuses the shared one
 * from {@link BaseXConfig}, so there's exactly one in the context.
 */
@Configuration
public class MeiliConfig {

    @Value("${meili.url}")
    private String url;

    @Value("${meili.key}")
    private String key;

    @Value("${meili.index}")
    private String index;

    @Bean
    public MeiliProperties meiliProperties() {
        return new MeiliProperties(url, key, index);
    }

    /**
     * Strongly typed holder for Meilisearch connection properties.
     */
    public record MeiliProperties(
         String url
        , String key
        , String index) {}
}
