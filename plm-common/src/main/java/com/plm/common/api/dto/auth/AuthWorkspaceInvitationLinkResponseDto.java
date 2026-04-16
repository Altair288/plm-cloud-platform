package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthWorkspaceInvitationLinkResponseDto {
    private UUID linkId;
    private UUID workspaceId;
    private String workspaceName;
    private String shareUrl;
    private String sourceScene;
    private String targetRoleCode;
    private OffsetDateTime expiresAt;
    private Integer usedCount;
    private Integer maxUseCount;
    private String status;
    private OffsetDateTime createdAt;
}