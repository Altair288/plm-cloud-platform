package com.plm.common.api.dto.category.subtree;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategorySubtreeFlatNodeDto {
    private UUID id;
    private UUID parentId;
    private String businessDomain;
    private String code;
    private String name;
    private String status;
    private Integer level;
    private Integer depth;
    private String path;
    private Boolean hasChildren;
    private Boolean leaf;
    private Integer sort;
}
