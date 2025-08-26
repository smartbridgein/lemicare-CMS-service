package com.lemicare.cms.filter;

import com.lemicare.cms.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            String orgId = jwt.getClaimAsString("organizationId");
            String branchId = jwt.getClaimAsString("branchId");
            String userId = jwt.getSubject();

            TenantContext.setContext(orgId, branchId, userId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // CRITICAL: Always clear the context after the request is complete
            // to prevent memory leaks in the thread pool.
            TenantContext.clear();
        }
    }
}

