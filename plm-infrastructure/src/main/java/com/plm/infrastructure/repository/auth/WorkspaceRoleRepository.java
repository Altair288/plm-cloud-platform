package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRoleRepository extends JpaRepository<WorkspaceRole, UUID> {
    Optional<WorkspaceRole> findByWorkspaceIdAndRoleCode(UUID workspaceId, String roleCode);

    List<WorkspaceRole> findByWorkspaceIdOrderByRoleCodeAsc(UUID workspaceId);
}