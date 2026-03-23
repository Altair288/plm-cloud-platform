package com.plm.common.api.dto.category;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class MetaCategoryNodeDto {
    private UUID id;
    private String businessDomain;
    private String code;
    private String name;
    private Integer level;
    private UUID parentId;
    private String path;
    private Boolean hasChildren;
    private Boolean leaf;
    private String status;
    private Integer sort;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
