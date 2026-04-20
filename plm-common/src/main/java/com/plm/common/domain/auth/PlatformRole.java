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
@Table(name = "platform_role", schema = "plm_platform")
@Getter
@Setter
public class PlatformRole {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 128)
    private String roleName;

    @Column(name = "role_status", nullable = false, length = 20)
    private String roleStatus = "ACTIVE";

    @Column(name = "built_in_flag", nullable = false)
    private Boolean builtInFlag = Boolean.FALSE;

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
        if (roleStatus == null || roleStatus.isBlank()) {
            roleStatus = "ACTIVE";
        }
        if (builtInFlag == null) {
            builtInFlag = Boolean.FALSE;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}