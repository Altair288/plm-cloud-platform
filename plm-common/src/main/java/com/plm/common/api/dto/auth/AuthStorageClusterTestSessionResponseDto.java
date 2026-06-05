package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthStorageClusterTestSessionResponseDto {
    private UUID testSessionId;
    private UUID clusterId;
    private String sessionStatus;
    private String objectKey;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String etag;
    private OffsetDateTime expiresAt;
    private OffsetDateTime completedAt;
}