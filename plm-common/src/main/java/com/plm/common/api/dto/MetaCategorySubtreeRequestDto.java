package com.plm.common.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategorySubtreeRequestDto {
    private UUID parentId;
    private Boolean includeRoot;
    private Integer maxDepth;
    private String status;
    private String mode;
    private Integer nodeLimit;
}
