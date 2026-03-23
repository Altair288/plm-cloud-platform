package com.plm.common.api.dto.category.subtree;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategorySubtreeResponseDto {
    private UUID parentId;
    private String mode;
    private Integer totalNodes;
    private Boolean truncated;
    private Integer depthReached;
    private String message;
    private Object data;
}
