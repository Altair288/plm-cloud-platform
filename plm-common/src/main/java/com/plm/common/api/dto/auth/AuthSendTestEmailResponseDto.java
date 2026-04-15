package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AuthSendTestEmailResponseDto {
    private String email;
    private String fromEmail;
    private String subject;
    private OffsetDateTime sentAt;
}