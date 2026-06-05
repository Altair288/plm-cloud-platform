package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthStorageClusterUpsertRequestDto {
    private String clusterCode;
    private String endpoint;
    private String publicEndpoint;
    private String regionName;
    private String bucketPrefix;
    private String testBucketName;
    private String secretRef;
    private String status;
}