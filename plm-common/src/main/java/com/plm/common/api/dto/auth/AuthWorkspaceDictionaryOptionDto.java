package com.plm.common.api.dto.auth;

import lombok.Data;

@Data
public class AuthWorkspaceDictionaryOptionDto {
    private String code;
    private String label;
    private String description;
    private Integer sortOrder;
    private Boolean isDefault;
}