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
@Table(name = "object_asset", schema = "plm_runtime")
@Getter
@Setter
public class ObjectAsset {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "bucket_id", nullable = false)
    private UUID bucketId;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "object_status", nullable = false, length = 20)
    private String objectStatus = "PENDING_UPLOAD";

    @Column(name = "biz_type", nullable = false, length = 64)
    private String bizType;

    @Column(name = "biz_ref_id")
    private UUID bizRefId;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "etag", length = 128)
    private String etag;

    @Column(name = "sha256", length = 128)
    private String sha256;

    @Column(name = "uploaded_by_user_id", nullable = false)
    private UUID uploadedByUserId;

    @Column(name = "visibility_scope", nullable = false, length = 20)
    private String visibilityScope = "WORKSPACE";

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
        if (objectStatus == null || objectStatus.isBlank()) {
            objectStatus = "PENDING_UPLOAD";
        }
        if (visibilityScope == null || visibilityScope.isBlank()) {
            visibilityScope = "WORKSPACE";
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