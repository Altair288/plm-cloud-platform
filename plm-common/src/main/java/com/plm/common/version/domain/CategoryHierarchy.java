package com.plm.common.version.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "category_hierarchy", schema = "plm_meta")
@IdClass(CategoryHierarchyId.class)
@Getter
@Setter
public class CategoryHierarchy {
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ancestor_def_id", nullable = false)
    private MetaCategoryDef ancestorDef;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "descendant_def_id", nullable = false)
    private MetaCategoryDef descendantDef;

    @Column(name = "distance", nullable = false)
    private Short distance;
}
