package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthPlatformAdminLoginResponseDto {
    private String platformToken;
    private String platformTokenName;
    private Boolean remember;
    private Long platformTokenExpireInSeconds;
    private AuthAdminSummaryDto admin;
}