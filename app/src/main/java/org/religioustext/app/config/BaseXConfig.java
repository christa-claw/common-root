package org.religioustext.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configures the BaseX XML database connection.
 * BaseX is accessed via its REST API using standard HTTP.
 */
@Configuration
public class BaseXConfig {

    @Value("${basex.uri}")
    private String uri;

    @Value("${basex.username}")
    private String username;

    @Value("${basex.password}")
    private String password;

    @Value("${basex.database}")
    private String database;

    @Bean
    public BaseXProperties baseXProperties() {
        return new BaseXProperties(uri, username, password, database);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Strongly typed holder for BaseX connection properties. A record's components are its
     * public accessors and fields (not ordinary parameters), so they are EXEMPT from the
     * {@code a/an/the} parameter-naming convention — {@code uri()}, not {@code anUri()}.
     */
    public record BaseXProperties(String uri, String username, String password, String database) {}
}
