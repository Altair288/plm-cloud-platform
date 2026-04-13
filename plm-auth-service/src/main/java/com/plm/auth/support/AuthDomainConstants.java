package com.plm.auth.support;

public final class AuthDomainConstants {
    public static final String USER_STATUS_ACTIVE = "ACTIVE";
    public static final String CREDENTIAL_TYPE_PASSWORD = "PASSWORD";
    public static final String LOGIN_TYPE_PLATFORM = "platform";
    public static final String LOGIN_TYPE_WORKSPACE = "workspace";
    public static final String LOGIN_RESULT_SUCCESS = "SUCCESS";
    public static final String LOGIN_RESULT_FAILED = "FAILED";
    public static final String WORKSPACE_STATUS_ACTIVE = "ACTIVE";
    public static final String WORKSPACE_MEMBER_STATUS_ACTIVE = "ACTIVE";
    public static final String WORKSPACE_JOIN_TYPE_OWNER = "OWNER";
    public static final String WORKSPACE_ROLE_STATUS_ACTIVE = "ACTIVE";
    public static final String WORKSPACE_ROLE_TYPE_SYSTEM = "SYSTEM";
    public static final String ROLE_CODE_WORKSPACE_OWNER = "workspace_owner";
    public static final String ROLE_CODE_WORKSPACE_ADMIN = "workspace_admin";
    public static final String ROLE_CODE_WORKSPACE_MEMBER = "workspace_member";
    public static final String ROLE_CODE_WORKSPACE_VIEWER = "workspace_viewer";
    public static final String VERIFICATION_PURPOSE_REGISTER = "REGISTER";
    public static final String VERIFICATION_CODE_STATUS_PENDING = "PENDING";
    public static final String VERIFICATION_CODE_STATUS_USED = "USED";
    public static final String VERIFICATION_CODE_STATUS_EXPIRED = "EXPIRED";
    public static final String VERIFICATION_CODE_STATUS_SUPERSEDED = "SUPERSEDED";

    private AuthDomainConstants() {
    }
}