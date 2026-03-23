package com.plm.common.api.dto.category;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategoryCopySourceMappingDto {
    private UUID sourceNodeId;
    private UUID createdNodeId;
    private UUID copiedFromCategoryId;
}