package com.lemicare.cms.context;

public class TenantContext {

    private static final ThreadLocal<String> organizationId = new ThreadLocal<>();
    private static final ThreadLocal<String> branchId = new ThreadLocal<>();
    private static final ThreadLocal<String> userId = new ThreadLocal<>();

    public static void setContext(String orgId, String branch, String user) {
        organizationId.set(orgId);
        branchId.set(branch);
        userId.set(user);
    }

    public static String getOrganizationId() {
        return organizationId.get();
    }

    public static String getBranchId() {
        return branchId.get();
    }

    public static String getUserId() {
        return userId.get();
    }

    public static void clear() {
        organizationId.remove();
        branchId.remove();
        userId.remove();
    }
}