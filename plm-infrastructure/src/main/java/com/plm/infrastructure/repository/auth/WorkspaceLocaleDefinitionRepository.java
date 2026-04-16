package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceLocaleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceLocaleDefinitionRepository extends JpaRepository<WorkspaceLocaleDefinition, String> {
    List<WorkspaceLocaleDefinition> findByEnabledTrueOrderBySortOrderAscCodeAsc();

    Optional<WorkspaceLocaleDefinition> findByCodeAndEnabledTrue(String code);

    Optional<WorkspaceLocaleDefinition> findFirstByEnabledTrueAndIsDefaultTrueOrderBySortOrderAscCodeAsc();
}