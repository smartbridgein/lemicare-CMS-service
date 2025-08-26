package com.lemicare.cms.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Configures a central RestTemplate bean for service-to-service communication.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * Creates a RestTemplate bean that is pre-configured to automatically
     * propagate the JWT Authorization header from the incoming request to any
     * outgoing request. This is the standard mechanism for securing internal
     * microservice calls.
     *
     * @param builder The RestTemplateBuilder provided by Spring Boot.
     * @return A configured RestTemplate instance.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .additionalInterceptors((request, body, execution) -> {
                    // Check if we are in an active HTTP request context.
                    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attrs != null) {
                        // Get the 'Authorization' header from the original incoming request.
                        String bearerToken = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                            // Add the same 'Authorization' header to the outgoing request.
                            request.getHeaders().add(HttpHeaders.AUTHORIZATION, bearerToken);
                        }
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}