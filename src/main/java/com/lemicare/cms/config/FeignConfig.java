package com.lemicare.cms.config;


import com.lemicare.cms.context.TenantContext;
import feign.*;
import feign.codec.ErrorDecoder;
import org.apache.http.HttpHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

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
        final ErrorDecoder defaultErrorDecoder = new ErrorDecoder.Default();
        return (methodKey, response) -> {
            int status = response.status();
            String reason = response.reason();

            byte[] bodyBytes = new byte[0]; // Default to empty byte array
            if (response.body() != null) {
                try (InputStream is = response.body().asInputStream()) {
                    bodyBytes = is.readAllBytes();
                } catch (IOException e) {
                    // Log this, but don't prevent decoding the error
                    System.err.println("Error reading Feign response body: " + e.getMessage());
                }
            }

            if (status == 400) {
                // Return FeignException.BadRequest with potentially empty bodyBytes
                return new FeignException.BadRequest(reason, response.request(), bodyBytes, response.headers());
            } else if (status == 401) {
                // Return FeignException.Unauthorized with potentially empty bodyBytes
                return new FeignException.Unauthorized(reason, response.request(), bodyBytes, response.headers());
            } else if (status == 404) {
                // Return FeignException.NotFound with potentially empty bodyBytes
                return new FeignException.NotFound(reason, response.request(), bodyBytes, response.headers());
            }
            // For any other status, use the default error decoder
            return defaultErrorDecoder.decode(methodKey, response);
        };
    }

    @Bean
    public RequestInterceptor tenantInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                String orgId = TenantContext.getOrganizationId();
                if (orgId != null) {
                    template.header("X-Organization-Id", orgId);
                }
            }
        };
    }

    @Bean
    public RequestInterceptor feignRequestInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                // 1. Propagate Authorization header from the SecurityContext (if present)
                // This is the most reliable way to get the JWT that was validated by the CMS's security.
                // Note: SecurityContextHolder.getContext() might be null in async scenarios.
                // For WebFlux/Reactive, you'd use ReactiveSecurityContextHolder.getContext().
                // Assuming your CMS is Servlet-based (from "nio-8086-exec-1" in logs)
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                    template.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue());
                    // Log to verify
                    // System.out.println("CMS Feign: Propagating JWT from SecurityContext for internal call.");
                } else {
                    // Fallback: If not found in SecurityContext, try RequestContextHolder
                    // This might happen if the SecurityContext is not fully propagated or in specific contexts.
                    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attributes != null) {
                        String authorizationHeader = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                        if (authorizationHeader != null && !authorizationHeader.isEmpty()) {
                            template.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
                            // System.out.println("CMS Feign: Propagating Authorization header from incoming request for internal call.");
                        }
                    }
                }


                // 2. Propagate custom tenant headers (X-Org-ID, X-User-ID, X-Branch-ID)
                // Assuming these are also available in the incoming request headers or derived from the JWT
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    // X-Org-ID
                    Optional.ofNullable(attributes.getRequest().getHeader("X-Org-ID"))
                            .ifPresent(orgId -> template.header("X-Org-ID", orgId));

                    // X-User-ID
                    Optional.ofNullable(attributes.getRequest().getHeader("X-User-ID"))
                            .ifPresent(userId -> template.header("X-User-ID", userId));

                    // X-Branch-ID
                    Optional.ofNullable(attributes.getRequest().getHeader("X-Branch-ID"))
                            .ifPresent(branchId -> template.header("X-Branch-ID", branchId));

                    // System.out.println("CMS Feign: Propagating custom headers for internal call.");
                } else {
                    // Fallback for async contexts where RequestContextHolder might be null.
                    // If you rely heavily on this in async, you'll need to use Reactor's Context propagation
                    // or a custom ThreadLocal (carefully).
                    // For now, this fallback assumes SecurityContextHolder is still the primary source for JWT.
                }
            }
        };
    }
}


