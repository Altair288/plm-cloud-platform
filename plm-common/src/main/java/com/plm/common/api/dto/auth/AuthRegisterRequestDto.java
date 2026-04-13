package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthRegisterRequestDto {
    private String username;
    private String displayName;
    private String password;
    private String confirmPassword;
    private String email;
    private String emailVerificationCode;
    private String phone;
}