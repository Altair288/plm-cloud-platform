package com.plm.common.version.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_lov_version", schema = "plm_meta")
@Getter
@Setter
public class MetaLovVersion {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lov_def_id", nullable = false)
    private MetaLovDef lovDef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_version_id", nullable = false)
    private MetaAttributeVersion attributeVersion;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "resolved_code_prefix", length = 192)
    private String resolvedCodePrefix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_json", nullable = false)
    private String valueJson;

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
        if (valueJson == null) valueJson = "{\"values\":[]}";
    }
}
