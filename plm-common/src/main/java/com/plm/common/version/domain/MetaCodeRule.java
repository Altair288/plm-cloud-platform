package com.plm.common.version.domain;

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
@Table(name = "meta_code_rule", schema = "plm_meta")
@Getter
@Setter
public class MetaCodeRule {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "business_domain", nullable = false, length = 64)
    private String businessDomain;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "pattern", nullable = false, length = 128)
    private String pattern;

    @Column(name = "inherit_from", length = 32)
    private String inheritFrom;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.FALSE;

    @Column(name = "remark")
    private String remark;

    @Column(name = "parent_rule_id")
    private UUID parentRuleId;

    @Column(name = "inherit_prefix", nullable = false)
    private Boolean inheritPrefix = Boolean.FALSE;

    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType = "GLOBAL";

    @Column(name = "scope_value", length = 128)
    private String scopeValue;

    @Column(name = "allow_manual_override", nullable = false)
    private Boolean allowManualOverride = Boolean.FALSE;

    @Column(name = "regex_pattern", length = 255)
    private String regexPattern;

    @Column(name = "max_length", nullable = false)
    private Integer maxLength = 64;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = "DRAFT";
        }
        if (scopeType == null) {
            scopeType = "GLOBAL";
        }
        if (active == null) {
            active = Boolean.FALSE;
        }
        if (allowManualOverride == null) {
            allowManualOverride = Boolean.FALSE;
        }
        if (inheritPrefix == null) {
            inheritPrefix = Boolean.FALSE;
        }
        if (maxLength == null) {
            maxLength = 64;
        }
    }
}