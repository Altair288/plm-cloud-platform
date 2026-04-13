package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.UUID;

@Data
public class AuthWorkspaceOptionDto {
    private UUID workspaceId;
    private String workspaceCode;
    private String workspaceName;
    private String workspaceStatus;
    private UUID workspaceMemberId;
    private String memberStatus;
    private Boolean isDefaultWorkspace;
}