package com.plm.common.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspace", schema = "plm_platform")
@Getter
@Setter
public class Workspace {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_code", nullable = false, length = 64)
    private String workspaceCode;

    @Column(name = "workspace_name", nullable = false, length = 128)
    private String workspaceName;

    @Column(name = "workspace_status", nullable = false, length = 20)
    private String workspaceStatus = "ACTIVE";

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "workspace_type", nullable = false, length = 20)
    private String workspaceType;

    @Column(name = "lifecycle_stage", nullable = false, length = 20)
    private String lifecycleStage;

    @Column(name = "default_locale", nullable = false, length = 16)
    private String defaultLocale;

    @Column(name = "default_timezone", nullable = false, length = 64)
    private String defaultTimezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json")
    private String configJson;

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
        if (workspaceStatus == null || workspaceStatus.isBlank()) {
            workspaceStatus = "ACTIVE";
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