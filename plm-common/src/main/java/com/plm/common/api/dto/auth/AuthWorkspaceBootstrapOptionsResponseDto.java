package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AuthWorkspaceBootstrapOptionsResponseDto {
    private List<AuthWorkspaceDictionaryOptionDto> workspaceTypes = new ArrayList<>();
    private List<AuthWorkspaceDictionaryOptionDto> locales = new ArrayList<>();
    private List<AuthWorkspaceDictionaryOptionDto> timezones = new ArrayList<>();
}