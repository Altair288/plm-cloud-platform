package com.plm.common.api.dto.storage;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class StorageObjectUploadIntentResponseDto {
    private UUID objectId;
    private String uploadToken;
    private String bucketName;
    private String objectKey;
    private String presignedUploadUrl;
    private OffsetDateTime expireAt;
}