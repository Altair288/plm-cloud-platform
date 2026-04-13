package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    boolean existsByWorkspaceCode(String workspaceCode);

    Optional<Workspace> findByWorkspaceCode(String workspaceCode);
}