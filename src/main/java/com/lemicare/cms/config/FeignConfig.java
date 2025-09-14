package com.lemicare.cms.config;

import feign.RetryableException;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized configuration for all Feign clients in this service.
 * This class defines the custom retry logic and error handling for
 * all outgoing inter-service communication.
 */
@Configuration
public class FeignConfig {

    /**
     * Defines the custom retry behavior for Feign clients.
     *
     * @return A configured Retryer instance.
     */
    @Bean
    public Retryer feignRetryer() {
        // This configuration will:
        // - Start with a 100ms delay.
        // - Wait a maximum of 1 second between retries.
        // - Attempt a total of 3 times (1 initial call + 2 retries).
        return new Retryer.Default(100, 1000, 3);
    }

    /**
     * Defines the custom error decoding logic.
     * This allows us to control which HTTP errors trigger a retry.
     *
     * @return A configured ErrorDecoder instance.
     */

    @Bean
    public ErrorDecoder feignErrorDecoder() {
        final ErrorDecoder defaultErrorDecoder = new ErrorDecoder.Default(); // Keep default behavior for others

        return (methodKey, response) -> {
            // Define which HTTP status codes represent transient errors that should be retried.
            int status = response.status();
            if (status == 503 // Service Unavailable
                    || status == 500 // Internal Server Error (often transient during restarts)
                    || status == 502 // Bad Gateway
                    || status == 504 // Gateway Timeout
                    || status == 429) { // Too Many Requests (if storefront has rate limiting)

                return new RetryableException(
                        status,
                        "Service responded with transient error, retrying...",
                        response.request().httpMethod(),
                        (Throwable) defaultErrorDecoder.decode(methodKey, response), // Decode actual exception
                        (Long) null,
                        response.request()
                );
            }

            // For all other errors, use Feign's default error handling (which typically throws FeignException)
            return defaultErrorDecoder.decode(methodKey, response);
        };
    }
}

