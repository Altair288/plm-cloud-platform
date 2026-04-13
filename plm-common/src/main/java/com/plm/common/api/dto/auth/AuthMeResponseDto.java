package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.List;

@Data
public class AuthMeResponseDto {
    private AuthUserSummaryDto user;
    private AuthWorkspaceOptionDto defaultWorkspace;
    private List<AuthWorkspaceOptionDto> workspaceOptions;
    private AuthWorkspaceSessionResponseDto currentWorkspace;
}