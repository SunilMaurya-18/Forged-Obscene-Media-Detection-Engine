package com.forgeddetect.config;

import com.forgeddetect.observability.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /** ml-service responses carry base64 images (heatmap, labelled copy of uploads up to 25 MB). */
    private static final int MAX_RESPONSE_BYTES = 48 * 1024 * 1024;

    /**
     * Prototype like Boot's own builder, so one caller's {@code baseUrl} never leaks into another's.
     * Boot's customizers are applied, which adds the {@code http.client.requests} metrics.
     */
    @Bean
    @Scope("prototype")
    public WebClient.Builder webClientBuilder(ObjectProvider<WebClientCustomizer> customizers) {
        WebClient.Builder builder = WebClient.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                // Runs on the calling thread when block() subscribes, so the servlet request's MDC is visible
                .filter((request, next) -> {
                    String id = MDC.get(RequestIdFilter.MDC_KEY);
                    return next.exchange(id == null || request.headers().containsKey(RequestIdFilter.HEADER)
                            ? request
                            : ClientRequest.from(request).header(RequestIdFilter.HEADER, id).build());
                });
        customizers.orderedStream().forEach(c -> c.customize(builder));
        return builder;
    }
}
