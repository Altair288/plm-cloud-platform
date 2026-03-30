package com.plm.common.version.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_code_rule_set", schema = "plm_meta")
@Getter
@Setter
public class MetaCodeRuleSet {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "business_domain", nullable = false, length = 64)
    private String businessDomain;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.FALSE;

    @Column(name = "remark")
    private String remark;

    @Column(name = "category_rule_code", nullable = false, length = 64)
    private String categoryRuleCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_rule_code", referencedColumnName = "code", insertable = false, updatable = false)
    private MetaCodeRule categoryRule;

    @Column(name = "attribute_rule_code", nullable = false, length = 64)
    private String attributeRuleCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_rule_code", referencedColumnName = "code", insertable = false, updatable = false)
    private MetaCodeRule attributeRule;

    @Column(name = "lov_rule_code", nullable = false, length = 64)
    private String lovRuleCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lov_rule_code", referencedColumnName = "code", insertable = false, updatable = false)
    private MetaCodeRule lovRule;

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
        if (active == null) {
            active = Boolean.FALSE;
        }
    }
}