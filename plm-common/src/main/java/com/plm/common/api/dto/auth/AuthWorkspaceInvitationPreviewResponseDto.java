package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthWorkspaceInvitationPreviewResponseDto {
    private UUID workspaceId;
    private String workspaceName;
    private String workspaceType;
    private String inviterDisplayName;
    private String inviteeEmailMasked;
    private String invitationStatus;
    private String sourceScene;
    private String targetRoleCode;
    private OffsetDateTime expiresAt;
    private boolean canAccept;
}