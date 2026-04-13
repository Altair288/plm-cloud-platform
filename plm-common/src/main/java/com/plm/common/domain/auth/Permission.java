package com.plm.common.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "permission", schema = "plm_platform")
@Getter
@Setter
public class Permission {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "permission_code", nullable = false, length = 96)
    private String permissionCode;

    @Column(name = "permission_name", nullable = false, length = 128)
    private String permissionName;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "module_code", nullable = false, length = 64)
    private String moduleCode;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}