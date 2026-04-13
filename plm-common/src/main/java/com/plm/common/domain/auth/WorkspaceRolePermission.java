package com.plm.common.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "workspace_role_permission", schema = "plm_platform")
@IdClass(WorkspaceRolePermissionId.class)
@Getter
@Setter
public class WorkspaceRolePermission {
    @Id
    @Column(name = "workspace_role_id", nullable = false)
    private UUID workspaceRoleId;

    @Id
    @Column(name = "permission_id", nullable = false)
    private UUID permissionId;
}