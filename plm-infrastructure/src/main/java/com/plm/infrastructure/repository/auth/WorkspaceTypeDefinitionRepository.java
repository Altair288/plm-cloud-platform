package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceTypeDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceTypeDefinitionRepository extends JpaRepository<WorkspaceTypeDefinition, String> {
    List<WorkspaceTypeDefinition> findByEnabledTrueOrderBySortOrderAscCodeAsc();

    Optional<WorkspaceTypeDefinition> findByCodeAndEnabledTrue(String code);

    Optional<WorkspaceTypeDefinition> findFirstByEnabledTrueAndIsDefaultTrueOrderBySortOrderAscCodeAsc();
}