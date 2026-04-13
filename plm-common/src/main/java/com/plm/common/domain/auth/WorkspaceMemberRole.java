package com.plm.common.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspace_member_role", schema = "plm_platform")
@IdClass(WorkspaceMemberRoleId.class)
@Getter
@Setter
public class WorkspaceMemberRole {
    @Id
    @Column(name = "workspace_member_id", nullable = false)
    private UUID workspaceMemberId;

    @Id
    @Column(name = "workspace_role_id", nullable = false)
    private UUID workspaceRoleId;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "assigned_by_user_id")
    private UUID assignedByUserId;

    @PrePersist
    public void prePersist() {
        if (assignedAt == null) {
            assignedAt = OffsetDateTime.now();
        }
    }
}