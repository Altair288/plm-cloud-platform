package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryBatchTransferItemResultDto {
    private String clientOperationId;
    private UUID sourceNodeId;
    private UUID normalizedSourceNodeId;
    private UUID targetParentId;
    private String action;
    private Boolean success;
    private Integer affectedNodeCount;
    private List<UUID> movedIds;
    private UUID createdRootId;
    private List<UUID> createdIds;
    private UUID copiedFromCategoryId;
    private List<MetaCategoryCopySourceMappingDto> sourceMappings;
    private List<MetaCategoryCodeMappingDto> codeMappings;
    private String code;
    private String message;
    private List<String> warning;
}