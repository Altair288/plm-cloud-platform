package com.plm.common.version.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_lov_def", schema = "plm_meta")
@Getter
@Setter
public class MetaLovDef {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_def_id", nullable = false)
    private MetaAttributeDef attributeDef;

    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    @Column(name = "source_attribute_key")
    private String sourceAttributeKey;

    @Column(name = "description")
    private String description;

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
