package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AuthAdminSummaryDto {
    private UUID id;
    private String username;
    private String displayName;
    private String email;
    private String phone;
    private String status;
    private List<String> roleCodes;
    private Boolean superAdmin;
}