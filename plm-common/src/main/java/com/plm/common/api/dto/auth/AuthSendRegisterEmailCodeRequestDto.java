package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthSendRegisterEmailCodeRequestDto {
    private String email;
}