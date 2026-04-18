package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthRegisterRequestDto {
    private String username;
    private String displayName;
    private String password;
    private String confirmPassword;
    private String passwordCiphertext;
    private String confirmPasswordCiphertext;
    private String encryptionKeyId;
    private String email;
    private String emailVerificationCode;
    private String phone;
}