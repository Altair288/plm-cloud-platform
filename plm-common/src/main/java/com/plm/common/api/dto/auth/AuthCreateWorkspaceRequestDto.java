package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthCreateWorkspaceRequestDto {
    private String workspaceName;
    private String workspaceCode;
    private String workspaceType;
    private String defaultLocale;
    private String defaultTimezone;
    private Boolean rememberAsDefault;
}