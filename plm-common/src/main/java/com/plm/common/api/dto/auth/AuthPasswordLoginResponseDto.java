package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.List;

@Data
public class AuthPasswordLoginResponseDto {
    private String platformToken;
    private String platformTokenName;
    private Boolean remember;
    private Long platformTokenExpireInSeconds;
    private AuthUserSummaryDto user;
    private AuthWorkspaceOptionDto defaultWorkspace;
    private List<AuthWorkspaceOptionDto> workspaceOptions;
    private AuthWorkspaceSessionResponseDto currentWorkspace;
}