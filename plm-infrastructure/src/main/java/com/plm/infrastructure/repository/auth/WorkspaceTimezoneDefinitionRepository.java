package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceTimezoneDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceTimezoneDefinitionRepository extends JpaRepository<WorkspaceTimezoneDefinition, String> {
    List<WorkspaceTimezoneDefinition> findByEnabledTrueOrderBySortOrderAscCodeAsc();

    Optional<WorkspaceTimezoneDefinition> findByCodeAndEnabledTrue(String code);

    Optional<WorkspaceTimezoneDefinition> findFirstByEnabledTrueAndIsDefaultTrueOrderBySortOrderAscCodeAsc();
}