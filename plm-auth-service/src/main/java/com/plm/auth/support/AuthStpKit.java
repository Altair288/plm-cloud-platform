package com.plm.auth.support;

import cn.dev33.satoken.stp.StpLogic;

import java.util.UUID;

public final class AuthStpKit {
    private static final String CURRENT_WORKSPACE_MEMBER_ID_KEY = "currentWorkspaceMemberId";

    public static final StpLogic PLATFORM = new StpLogic("platform");
    public static final StpLogic WORKSPACE = new StpLogic("workspace");

    private AuthStpKit() {
    }

    public static UUID requirePlatformUserId() {
        PLATFORM.checkLogin();
        return UUID.fromString(PLATFORM.getLoginIdAsString());
    }

    public static UUID currentWorkspaceMemberIdOrNull() {
        Object fromPlatformSession = PLATFORM.getSession().get(CURRENT_WORKSPACE_MEMBER_ID_KEY);
        if (fromPlatformSession != null) {
            return UUID.fromString(String.valueOf(fromPlatformSession));
        }
        Object loginId = WORKSPACE.getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        return UUID.fromString(String.valueOf(loginId));
    }

    public static void bindCurrentWorkspaceMemberId(UUID workspaceMemberId) {
        PLATFORM.getSession().set(CURRENT_WORKSPACE_MEMBER_ID_KEY, workspaceMemberId.toString());
    }

    public static void clearCurrentWorkspaceMemberId() {
        PLATFORM.getSession().delete(CURRENT_WORKSPACE_MEMBER_ID_KEY);
    }
}