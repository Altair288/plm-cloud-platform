package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthStorageClusterResponseDto {
    private UUID id;
    private String clusterCode;
    private String providerType;
    private String endpoint;
    private String publicEndpoint;
    private String regionName;
    private String bucketPrefix;
    private String testBucketName;
    private String secretRefPreview;
    private String status;
    private String healthStatus;
    private OffsetDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestErrorCode;
    private String lastTestErrorMessage;
    private OffsetDateTime lastCheckedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}