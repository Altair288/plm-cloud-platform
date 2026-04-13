package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AuthWorkspaceSessionResponseDto {
    private String workspaceToken;
    private String workspaceTokenName;
    private UUID workspaceId;
    private String workspaceCode;
    private String workspaceName;
    private UUID workspaceMemberId;
    private List<String> roleCodes;
}