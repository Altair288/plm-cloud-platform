package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthPasswordEncryptionKeyResponseDto {
    private String keyId;
    private String algorithm;
    private String transformation;
    private String publicKeyBase64;
    private boolean plaintextFallbackAllowed;
}