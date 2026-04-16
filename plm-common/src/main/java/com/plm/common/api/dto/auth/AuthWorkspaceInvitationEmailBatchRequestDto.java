package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AuthWorkspaceInvitationEmailBatchRequestDto {
    private UUID workspaceId;
    private List<String> emails;
    private String targetRoleCode;
    private String sourceScene;
}