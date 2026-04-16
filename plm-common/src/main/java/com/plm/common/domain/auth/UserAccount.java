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
@Table(name = "user_account", schema = "plm_platform")
@Getter
@Setter
public class UserAccount {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "email", length = 128)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType = "LOCAL";

    @Column(name = "is_first_login", nullable = false)
    private Boolean isFirstLogin = Boolean.TRUE;

    @Column(name = "workspace_count", nullable = false)
    private Integer workspaceCount = 0;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

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
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = "LOCAL";
        }
        if (isFirstLogin == null) {
            isFirstLogin = Boolean.TRUE;
        }
        if (workspaceCount == null || workspaceCount < 0) {
            workspaceCount = 0;
        }
        if (registeredAt == null) {
            registeredAt = now;
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