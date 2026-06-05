package com.plm.common.api.dto.storage;

import lombok.Data;

import java.util.UUID;

@Data
public class StorageObjectUploadIntentRequestDto {
    private String bizType;
    private UUID bizRefId;
    private String fileName;
    private String contentType;
    private Long expectedSize;
}