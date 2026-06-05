package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthStorageClusterTestUploadRequestDto {
    private String originalFileName;
    private String contentType;
    private Long fileSize;
}