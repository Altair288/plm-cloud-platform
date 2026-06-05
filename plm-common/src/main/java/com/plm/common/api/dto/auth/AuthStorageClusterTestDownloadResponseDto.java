package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AuthStorageClusterTestDownloadResponseDto {
    private String downloadUrl;
    private OffsetDateTime expiresAt;
}