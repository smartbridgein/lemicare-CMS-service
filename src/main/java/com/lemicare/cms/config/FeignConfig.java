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

    // Define constants for custom headers
    private static final String X_ORGANIZATION_ID_HEADER = "X-Organization-Id";
    private static final String X_USER_ID_HEADER = "X-User-Id";
    private static final String X_BRANCH_ID_HEADER = "X-Branch-Id";

    @Bean
    public Retryer feignRetryer() {
        return new Retryer.Default(100, 1000, 3); // 100ms initial, max 1s, 3 attempts (1 initial + 2 retries)
    }

    @Bean
    public ErrorDecoder feignErrorDecoder() {
        return (methodKey, response) -> {
            String body = "";
            try {
                if (response.body() != null) {
                    // It's safer to read the body as a string for logging,
                    // but the default errorStatus also reads it.
                    // If you need the body for custom exception types, keep this.
                    body = Util.toString(response.body().asReader());
                }
            } catch (Exception ignored) {
                // Ignore errors during body reading for logging, just log without body
            }

            System.err.println("\n=== FEIGN ERROR ===");
            System.err.println("URL: " + response.request().url());
            System.err.println("Method Key: " + methodKey);
            System.err.println("Status: " + response.status());
            System.err.println("Reason: " + response.reason());
            System.err.println("Headers: " + response.headers());
            System.err.println("Body: " + body); // Include body in error log
            System.err.println("===================\n");

            // Use FeignException.errorStatus to return the appropriate FeignException subclass
            // e.g., FeignException.BadRequest for 400, FeignException.Unauthorized for 401, etc.
            // This is generally sufficient and allows downstream services to catch specific exceptions.
            return FeignException.errorStatus(methodKey, response);
        };
    }

    /**
     * Consolidate all header propagation into a single interceptor.
     * This interceptor will propagate:
     * 1. The original Authorization (JWT) header.
     * 2. Custom tenant context headers (OrgId, UserId, BranchId) from the CMS's TenantContext.
     */
    @Bean
    public RequestInterceptor feignClientInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                // 1. Propagate Authorization header (JWT)
                // Get the JWT from the SecurityContext, which holds the authenticated user's details
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                    template.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue());
                    // Debugging line
                    // System.out.println("CMS Feign: Propagating Authorization header (JWT) for internal call.");
                } else {
                    // Log a warning if no JWT is found, unless it's an unauthenticated internal call
                    // System.out.println("CMS Feign: No JWT found in SecurityContext for outgoing request.");
                }

                // 2. Propagate custom tenant/user context headers from the CMS's TenantContext
                // The CMS's TenantFilter should have already populated these from the incoming JWT.
                String orgId = TenantContext.getOrganizationId();
                String userId = TenantContext.getUserId();
                String branchId = TenantContext.getBranchId();

                if (orgId != null) {
                    template.header(X_ORGANIZATION_ID_HEADER, orgId);
                }
                if (userId != null) {
                    template.header(X_USER_ID_HEADER, userId);
                }
                if (branchId != null) {
                    template.header(X_BRANCH_ID_HEADER, branchId);
                }
                // Debugging line
                // System.out.printf("CMS Feign: Propagating Tenant Headers - Org: %s, User: %s, Branch: %s%n", orgId, userId, branchId);
            }
        };
    }
}


