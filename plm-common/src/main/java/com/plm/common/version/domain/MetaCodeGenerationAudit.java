package com.plm.common.version.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_code_generation_audit", schema = "plm_meta")
@Getter
@Setter
public class MetaCodeGenerationAudit {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "rule_code", nullable = false, length = 64)
    private String ruleCode;

    @Column(name = "rule_version_no", nullable = false)
    private Integer ruleVersionNo;

    @Column(name = "generated_code", nullable = false, length = 128)
    private String generatedCode;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json")
    private String contextJson;

    @Column(name = "manual_override_flag", nullable = false)
    private Boolean manualOverrideFlag = Boolean.FALSE;

    @Column(name = "frozen_flag", nullable = false)
    private Boolean frozenFlag = Boolean.FALSE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (manualOverrideFlag == null) {
            manualOverrideFlag = Boolean.FALSE;
        }
        if (frozenFlag == null) {
            frozenFlag = Boolean.FALSE;
        }
    }
}