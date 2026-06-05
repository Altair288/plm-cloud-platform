package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthStorageClusterTestUploadResponseDto {
    private UUID testSessionId;
    private String uploadToken;
    private String objectKey;
    private String uploadUrl;
    private OffsetDateTime expiresAt;
}