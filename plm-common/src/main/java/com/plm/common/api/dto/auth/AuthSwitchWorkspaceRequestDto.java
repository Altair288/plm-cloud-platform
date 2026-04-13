package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.UUID;

@Data
public class AuthSwitchWorkspaceRequestDto {
    private UUID workspaceId;
    private Boolean rememberAsDefault;
}