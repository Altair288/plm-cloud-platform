package com.plm.common.domain;

import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "attribute", schema = "plm",
    indexes = {
        @Index(name = "idx_attribute_category_code", columnList = "category_id, code", unique = true)
    })
@Getter
@Setter
public class Attribute {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "code", nullable = false, length = 96)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "lov_code", length = 64)
    private String lovCode;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (sortOrder == null) sortOrder = 0;
    }
}
