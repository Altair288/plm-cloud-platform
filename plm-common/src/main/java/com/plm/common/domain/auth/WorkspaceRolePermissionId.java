package com.plm.common.domain.auth;

import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

@Data
public class WorkspaceRolePermissionId implements Serializable {
    private UUID workspaceRoleId;
    private UUID permissionId;
}