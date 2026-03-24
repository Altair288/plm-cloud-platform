package com.plm.common.version.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_attribute_def", schema = "plm_meta")
@Getter
@Setter
public class MetaAttributeDef {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_def_id", nullable = false)
    private MetaCategoryDef categoryDef;

    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    @Column(name = "lov_flag", nullable = false)
    private Boolean lovFlag = Boolean.FALSE;

    @Column(name = "auto_bind_key")
    private String autoBindKey;

    @Column(name = "key_manual_override", nullable = false)
    private Boolean keyManualOverride = Boolean.FALSE;

    @Column(name = "key_frozen", nullable = false)
    private Boolean keyFrozen = Boolean.FALSE;

    @Column(name = "generated_rule_code", length = 64)
    private String generatedRuleCode;

    @Column(name = "generated_rule_version_no")
    private Integer generatedRuleVersionNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (keyManualOverride == null) keyManualOverride = Boolean.FALSE;
        if (keyFrozen == null) keyFrozen = Boolean.FALSE;
    }
}
