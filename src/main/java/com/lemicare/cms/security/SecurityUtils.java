package com.lemicare.cms.security;

import com.lemicare.cms.context.TenantContext;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static String getOrganizationId() {
        String orgId = TenantContext.getOrganizationId();
        if (orgId == null) {
            throw new SecurityException("Organization ID not found in security context.");
        }
        return orgId;
    }

    public static String getBranchId() {
        String branchId = TenantContext.getBranchId();
        if (branchId == null) {
            throw new SecurityException("Branch ID not found in security context.");
        }
        return branchId;
    }

    public static String getUserId() {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new SecurityException("User ID not found in security context.");
        }
        return userId;
    }
}


