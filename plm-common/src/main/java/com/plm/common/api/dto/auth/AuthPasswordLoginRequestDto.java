package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthPasswordLoginRequestDto {
    private String identifier;
    private String password;
}