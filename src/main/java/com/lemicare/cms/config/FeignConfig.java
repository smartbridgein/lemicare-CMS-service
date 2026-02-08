package com.lemicare.cms.config;


import com.lemicare.cms.context.TenantContext;
import com.lemicare.cms.exception.InventoryClientException;
import com.lemicare.cms.exception.ServiceCommunicationException;
import feign.*;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
            String body = null;

            try {
                if (response.body() != null) {
                    body = Util.toString(response.body().asReader());
                }
            } catch (IOException ignored) {}

            // Log (already good)
            log.error("Feign error | method={} | status={} | body={}",
                    methodKey, response.status(), body);

            // INVENTORY CONFLICT
            if (response.status() == 409) {
                return new InventoryClientException(
                        body != null && !body.isBlank()
                                ? body
                                : "Insufficient stock"
                );
            }

            // Other downstream failures
            if (response.status() >= 500) {
                return new ServiceCommunicationException(
                        "Downstream service error: " + body
                );
            }

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


