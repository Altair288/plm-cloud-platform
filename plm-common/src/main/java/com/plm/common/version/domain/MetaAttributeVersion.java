package com.plm.common.version.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_attribute_version", schema = "plm_meta")
@Getter
@Setter
public class MetaAttributeVersion {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_def_id", nullable = false)
    private MetaAttributeDef attributeDef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_version_id", nullable = false)
    private MetaCategoryVersion categoryVersion;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "resolved_code_prefix", length = 128)
    private String resolvedCodePrefix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structure_json", nullable = false)
    private String structureJson;

    @Column(name = "hash", length = 64)
    private String hash;

    @Column(name = "is_latest", nullable = false)
    private Boolean isLatest = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (versionNo == null) versionNo = 1;
        if (structureJson == null) structureJson = "{}";
    }
}
