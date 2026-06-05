package com.plm.common.api.dto.storage;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class StorageObjectDownloadUrlResponseDto {
    private UUID objectId;
    private String downloadUrl;
    private OffsetDateTime expireAt;
}