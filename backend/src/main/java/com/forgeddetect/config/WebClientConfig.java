package com.forgeddetect.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /** ml-service responses carry base64 images (heatmap, labelled copy of uploads up to 25 MB). */
    private static final int MAX_RESPONSE_BYTES = 48 * 1024 * 1024;

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES));
    }
}
