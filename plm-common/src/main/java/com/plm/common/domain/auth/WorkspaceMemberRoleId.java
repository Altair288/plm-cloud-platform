package com.plm.common.domain.auth;

import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

@Data
public class WorkspaceMemberRoleId implements Serializable {
    private UUID workspaceMemberId;
    private UUID workspaceRoleId;
}