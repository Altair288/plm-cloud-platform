package com.plm.common.api.dto.storage;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class StorageObjectResponseDto {
    private UUID objectId;
    private UUID workspaceId;
    private String objectKey;
    private String objectStatus;
    private String bizType;
    private UUID bizRefId;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String etag;
    private String visibilityScope;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}