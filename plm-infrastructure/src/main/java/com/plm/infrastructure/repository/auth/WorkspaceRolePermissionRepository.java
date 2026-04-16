package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceRolePermission;
import com.plm.common.domain.auth.WorkspaceRolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkspaceRolePermissionRepository extends JpaRepository<WorkspaceRolePermission, WorkspaceRolePermissionId> {
    @Query("""
            select p.permissionCode
            from WorkspaceRolePermission wrp
            join Permission p on p.id = wrp.permissionId
            where wrp.workspaceRoleId = :workspaceRoleId
            order by p.permissionCode asc
            """)
    List<String> findPermissionCodesByWorkspaceRoleId(@Param("workspaceRoleId") UUID workspaceRoleId);

        @Query("""
            select distinct p.permissionCode
            from WorkspaceMemberRole wmr
            join WorkspaceRolePermission wrp on wrp.workspaceRoleId = wmr.workspaceRoleId
            join Permission p on p.id = wrp.permissionId
            where wmr.workspaceMemberId = :workspaceMemberId
            order by p.permissionCode asc
            """)
        List<String> findPermissionCodesByWorkspaceMemberId(@Param("workspaceMemberId") UUID workspaceMemberId);
}