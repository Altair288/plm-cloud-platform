package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.UUID;

@Data
public class AuthUserSummaryDto {
    private UUID id;
    private String username;
    private String displayName;
    private String email;
    private String phone;
    private String status;
    private Boolean isFirstLogin;
    private Integer workspaceCount;
}