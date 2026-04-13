package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthRegisterResponseDto {
    private UUID userId;
    private String username;
    private String displayName;
    private OffsetDateTime registeredAt;
}