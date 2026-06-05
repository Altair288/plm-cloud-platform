package com.plm.common.domain.storage;

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
@Table(name = "object_upload_session", schema = "plm_runtime")
@Getter
@Setter
public class ObjectUploadSession {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "session_status", nullable = false, length = 20)
    private String sessionStatus = "INITIATED";

    @Column(name = "upload_token", nullable = false, length = 128)
    private String uploadToken;

    @Column(name = "presigned_method", nullable = false, length = 10)
    private String presignedMethod = "PUT";

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "expected_content_type", length = 128)
    private String expectedContentType;

    @Column(name = "expected_max_size")
    private Long expectedMaxSize;

    @Column(name = "created_by_user_id", nullable = false)
    private UUID createdByUserId;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

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
        if (sessionStatus == null || sessionStatus.isBlank()) {
            sessionStatus = "INITIATED";
        }
        if (presignedMethod == null || presignedMethod.isBlank()) {
            presignedMethod = "PUT";
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