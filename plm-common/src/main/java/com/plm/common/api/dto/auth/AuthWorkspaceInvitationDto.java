package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthWorkspaceInvitationDto {
    private UUID id;
    private UUID workspaceId;
    private String workspaceName;
    private String inviteeEmail;
    private String inviteeDisplayName;
    private String inviterDisplayName;
    private String invitationStatus;
    private String sourceScene;
    private String invitationChannel;
    private String targetRoleCode;
    private UUID batchId;
    private OffsetDateTime expiresAt;
    private OffsetDateTime sentAt;
    private OffsetDateTime acceptedAt;
    private UUID acceptedByUserId;
    private OffsetDateTime canceledAt;
    private UUID canceledByUserId;
    private String cancelReason;
    private OffsetDateTime createdAt;
}