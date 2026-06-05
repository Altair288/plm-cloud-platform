package com.plm.common.api.dto.auth;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuthStorageClusterTestConnectionResponseDto {
    private UUID clusterId;
    private String clusterCode;
    private String testBucketName;
    private String healthStatus;
    private String lastTestStatus;
    private OffsetDateTime testedAt;
}