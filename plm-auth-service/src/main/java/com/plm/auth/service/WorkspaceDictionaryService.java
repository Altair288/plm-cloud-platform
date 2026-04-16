package com.plm.auth.service;

import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthWorkspaceBootstrapOptionsResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceDictionaryOptionDto;
import com.plm.common.domain.auth.WorkspaceLocaleDefinition;
import com.plm.common.domain.auth.WorkspaceTimezoneDefinition;
import com.plm.common.domain.auth.WorkspaceTypeDefinition;
import com.plm.infrastructure.repository.auth.WorkspaceLocaleDefinitionRepository;
import com.plm.infrastructure.repository.auth.WorkspaceTimezoneDefinitionRepository;
import com.plm.infrastructure.repository.auth.WorkspaceTypeDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class WorkspaceDictionaryService {

    private final WorkspaceTypeDefinitionRepository workspaceTypeDefinitionRepository;
    private final WorkspaceLocaleDefinitionRepository workspaceLocaleDefinitionRepository;
    private final WorkspaceTimezoneDefinitionRepository workspaceTimezoneDefinitionRepository;

    public WorkspaceDictionaryService(WorkspaceTypeDefinitionRepository workspaceTypeDefinitionRepository,
                                      WorkspaceLocaleDefinitionRepository workspaceLocaleDefinitionRepository,
                                      WorkspaceTimezoneDefinitionRepository workspaceTimezoneDefinitionRepository) {
        this.workspaceTypeDefinitionRepository = workspaceTypeDefinitionRepository;
        this.workspaceLocaleDefinitionRepository = workspaceLocaleDefinitionRepository;
        this.workspaceTimezoneDefinitionRepository = workspaceTimezoneDefinitionRepository;
    }

    public AuthWorkspaceBootstrapOptionsResponseDto getWorkspaceBootstrapOptions() {
        AuthWorkspaceBootstrapOptionsResponseDto response = new AuthWorkspaceBootstrapOptionsResponseDto();
        response.setWorkspaceTypes(workspaceTypeDefinitionRepository.findByEnabledTrueOrderBySortOrderAscCodeAsc()
                .stream()
                .map(this::toOption)
                .toList());
        response.setLocales(workspaceLocaleDefinitionRepository.findByEnabledTrueOrderBySortOrderAscCodeAsc()
                .stream()
                .map(this::toOption)
                .toList());
        response.setTimezones(workspaceTimezoneDefinitionRepository.findByEnabledTrueOrderBySortOrderAscCodeAsc()
                .stream()
                .map(this::toOption)
                .toList());
        return response;
    }

    public String resolveWorkspaceTypeCode(String requestedCode) {
        String normalized = AuthNormalizer.trimToNull(requestedCode);
        if (normalized == null) {
            return workspaceTypeDefinitionRepository.findFirstByEnabledTrueAndIsDefaultTrueOrderBySortOrderAscCodeAsc()
                    .map(WorkspaceTypeDefinition::getCode)
                    .orElseThrow(() -> new IllegalStateException("default workspace type is not configured"));
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return workspaceTypeDefinitionRepository.findByCodeAndEnabledTrue(normalized)
                .map(WorkspaceTypeDefinition::getCode)
                .orElseThrow(() -> new IllegalArgumentException("workspaceType is invalid"));
    }

    public String resolveWorkspaceLocaleCode(String requestedCode) {
        String normalized = AuthNormalizer.trimToNull(requestedCode);
        if (normalized == null) {
            return workspaceLocaleDefinitionRepository.findFirstByEnabledTrueAndIsDefaultTrueOrderBySortOrderAscCodeAsc()
                    .map(WorkspaceLocaleDefinition::getCode)
                    .orElseThrow(() -> new IllegalStateException("default workspace locale is not configured"));
        }
        return workspaceLocaleDefinitionRepository.findByCodeAndEnabledTrue(normalized)
                .map(WorkspaceLocaleDefinition::getCode)
                .orElseThrow(() -> new IllegalArgumentException("defaultLocale is invalid"));
    }

    public String resolveWorkspaceTimezoneCode(String requestedCode) {
        String normalized = AuthNormalizer.trimToNull(requestedCode);
        if (normalized == null) {
            return workspaceTimezoneDefinitionRepository.findFirstByEnabledTrueAndIsDefaultTrueOrderBySortOrderAscCodeAsc()
                    .map(WorkspaceTimezoneDefinition::getCode)
                    .orElseThrow(() -> new IllegalStateException("default workspace timezone is not configured"));
        }
        return workspaceTimezoneDefinitionRepository.findByCodeAndEnabledTrue(normalized)
                .map(WorkspaceTimezoneDefinition::getCode)
                .orElseThrow(() -> new IllegalArgumentException("defaultTimezone is invalid"));
    }

    private AuthWorkspaceDictionaryOptionDto toOption(WorkspaceTypeDefinition definition) {
        AuthWorkspaceDictionaryOptionDto option = new AuthWorkspaceDictionaryOptionDto();
        option.setCode(definition.getCode());
        option.setLabel(definition.getLabel());
        option.setDescription(definition.getDescription());
        option.setSortOrder(definition.getSortOrder());
        option.setIsDefault(definition.getIsDefault());
        return option;
    }

    private AuthWorkspaceDictionaryOptionDto toOption(WorkspaceLocaleDefinition definition) {
        AuthWorkspaceDictionaryOptionDto option = new AuthWorkspaceDictionaryOptionDto();
        option.setCode(definition.getCode());
        option.setLabel(definition.getLabel());
        option.setDescription(definition.getDescription());
        option.setSortOrder(definition.getSortOrder());
        option.setIsDefault(definition.getIsDefault());
        return option;
    }

    private AuthWorkspaceDictionaryOptionDto toOption(WorkspaceTimezoneDefinition definition) {
        AuthWorkspaceDictionaryOptionDto option = new AuthWorkspaceDictionaryOptionDto();
        option.setCode(definition.getCode());
        option.setLabel(definition.getLabel());
        option.setDescription(definition.getDescription());
        option.setSortOrder(definition.getSortOrder());
        option.setIsDefault(definition.getIsDefault());
        return option;
    }
}