package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthWorkspaceInvitationLinkPreviewResponseDto {
    private UUID workspaceId;
    private String workspaceName;
    private String workspaceType;
    private String inviterDisplayName;
    private String sourceScene;
    private String targetRoleCode;
    private OffsetDateTime expiresAt;
    private Integer usedCount;
    private Integer maxUseCount;
    private String status;
    private boolean canAccept;
}