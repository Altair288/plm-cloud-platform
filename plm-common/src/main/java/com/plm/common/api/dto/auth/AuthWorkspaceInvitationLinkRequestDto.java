package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.UUID;

@Data
public class AuthWorkspaceInvitationLinkRequestDto {
    private UUID workspaceId;
    private String sourceScene;
    private String targetRoleCode;
    private Integer expiresInHours;
    private Integer maxUseCount;
}