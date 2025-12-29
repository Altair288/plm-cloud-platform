package com.plm.common.version.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_category_def", schema = "plm_meta")
@Getter
@Setter
public class MetaCategoryDef {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code_key", nullable = false, length = 64, unique = true)
    private String codeKey;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_def_id")
    private MetaCategoryDef parent;

    @Column(name = "path")
    private String path; // 例如 /A/A01/A01.01

    @Column(name = "depth")
    private Short depth; // 根=0

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "full_path_name")
    private String fullPathName; // 例如 产成品/激光系统/光钟激光系统

    @Column(name = "is_leaf", nullable = false)
    private Boolean isLeaf = Boolean.TRUE;

    @Column(name = "external_code", length = 64)
    private String externalCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
