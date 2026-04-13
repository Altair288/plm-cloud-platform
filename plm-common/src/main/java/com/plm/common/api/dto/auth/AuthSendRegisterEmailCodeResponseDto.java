package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AuthSendRegisterEmailCodeResponseDto {
    private String email;
    private String maskedEmail;
    private OffsetDateTime expiresAt;
    private Long expireInSeconds;
    private Long resendCooldownSeconds;
}