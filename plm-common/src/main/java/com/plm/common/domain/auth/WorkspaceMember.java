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
@Table(name = "workspace_member", schema = "plm_platform")
@Getter
@Setter
public class WorkspaceMember {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "member_status", nullable = false, length = 20)
    private String memberStatus = "ACTIVE";

    @Column(name = "join_type", nullable = false, length = 20)
    private String joinType;

    @Column(name = "joined_at")
    private OffsetDateTime joinedAt;

    @Column(name = "invited_by_user_id")
    private UUID invitedByUserId;

    @Column(name = "is_default_workspace", nullable = false)
    private Boolean isDefaultWorkspace = Boolean.FALSE;

    @Column(name = "remark", length = 255)
    private String remark;

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
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (memberStatus == null || memberStatus.isBlank()) {
            memberStatus = "ACTIVE";
        }
        if (isDefaultWorkspace == null) {
            isDefaultWorkspace = Boolean.FALSE;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}