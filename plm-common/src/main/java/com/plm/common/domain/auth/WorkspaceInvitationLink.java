package com.plm.common.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspace_invitation_link", schema = "plm_platform")
@Getter
@Setter
public class WorkspaceInvitationLink {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "invited_by_user_id", nullable = false)
    private UUID invitedByUserId;

    @Column(name = "source_scene", nullable = false, length = 20)
    private String sourceScene = "WORKSPACE";

    @Column(name = "link_status", nullable = false, length = 20)
    private String linkStatus = "ACTIVE";

    @Column(name = "invitation_token", nullable = false, length = 128)
    private String invitationToken;

    @Column(name = "target_role_code", nullable = false, length = 64)
    private String targetRoleCode = "workspace_member";

    @Column(name = "max_use_count")
    private Integer maxUseCount;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (sourceScene == null || sourceScene.isBlank()) {
            sourceScene = "WORKSPACE";
        }
        if (linkStatus == null || linkStatus.isBlank()) {
            linkStatus = "ACTIVE";
        }
        if (targetRoleCode == null || targetRoleCode.isBlank()) {
            targetRoleCode = "workspace_member";
        }
        if (usedCount == null || usedCount < 0) {
            usedCount = 0;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}