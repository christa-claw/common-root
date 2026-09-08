package org.religioustext.ingestion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Infrastructure beans for the ingestion module.
 */
@Configuration
public class IngestionConfig {

    @Bean
    public RestTemplate restTemplate() {
        final RestTemplate restTemplate = new RestTemplate();

        // bible.helloao.org returns application/json with text/html content-type
        // Add a converter that accepts both
        final MappingJackson2HttpMessageConverter converter =
            new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(List.of(
             MediaType.APPLICATION_JSON
            , MediaType.TEXT_HTML
            , MediaType.TEXT_PLAIN
            , MediaType.ALL));

        final List converters = new ArrayList<>(restTemplate.getMessageConverters());
        converters.add(0, converter);
        restTemplate.setMessageConverters(converters);

        return restTemplate;
    }
}
