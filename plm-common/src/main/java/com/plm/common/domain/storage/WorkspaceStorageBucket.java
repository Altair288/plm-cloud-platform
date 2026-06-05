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
@Table(name = "workspace_storage_bucket", schema = "plm_platform")
@Getter
@Setter
public class WorkspaceStorageBucket {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(name = "bucket_name", length = 128)
    private String bucketName;

    @Column(name = "bucket_status", nullable = false, length = 20)
    private String bucketStatus = "PENDING";

    @Column(name = "provision_error_code", length = 64)
    private String provisionErrorCode;

    @Column(name = "provision_error_message", length = 512)
    private String provisionErrorMessage;

    @Column(name = "quota_bytes")
    private Long quotaBytes;

    @Column(name = "used_bytes", nullable = false)
    private Long usedBytes = 0L;

    @Column(name = "object_count", nullable = false)
    private Long objectCount = 0L;

    @Column(name = "provisioned_at")
    private OffsetDateTime provisionedAt;

    @Column(name = "frozen_at")
    private OffsetDateTime frozenAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

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
        if (bucketStatus == null || bucketStatus.isBlank()) {
            bucketStatus = "PENDING";
        }
        if (usedBytes == null || usedBytes < 0) {
            usedBytes = 0L;
        }
        if (objectCount == null || objectCount < 0) {
            objectCount = 0L;
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