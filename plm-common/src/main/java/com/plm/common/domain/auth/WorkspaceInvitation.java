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
@Table(name = "workspace_invitation", schema = "plm_platform")
@Getter
@Setter
public class WorkspaceInvitation {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "invitee_email", nullable = false, length = 128)
    private String inviteeEmail;

    @Column(name = "invitee_display_name", length = 128)
    private String inviteeDisplayName;

    @Column(name = "invited_by_user_id", nullable = false)
    private UUID invitedByUserId;

    @Column(name = "source_scene", nullable = false, length = 20)
    private String sourceScene = "WORKSPACE";

    @Column(name = "invitation_channel", nullable = false, length = 20)
    private String invitationChannel = "EMAIL";

    @Column(name = "target_role_code", nullable = false, length = 64)
    private String targetRoleCode = "workspace_member";

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "invitation_status", nullable = false, length = 20)
    private String invitationStatus = "PENDING";

    @Column(name = "invitation_token", nullable = false, length = 128)
    private String invitationToken;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "accepted_by_user_id")
    private UUID acceptedByUserId;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "canceled_by_user_id")
    private UUID canceledByUserId;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

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
        if (sourceScene == null || sourceScene.isBlank()) {
            sourceScene = "WORKSPACE";
        }
        if (invitationChannel == null || invitationChannel.isBlank()) {
            invitationChannel = "EMAIL";
        }
        if (targetRoleCode == null || targetRoleCode.isBlank()) {
            targetRoleCode = "workspace_member";
        }
        if (invitationStatus == null || invitationStatus.isBlank()) {
            invitationStatus = "PENDING";
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