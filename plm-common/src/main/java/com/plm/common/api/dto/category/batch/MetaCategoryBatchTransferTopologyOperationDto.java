package com.plm.common.api.dto.category.batch;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryBatchTransferTopologyOperationDto {
    private String operationId;
    private UUID sourceNodeId;
    private UUID targetParentId;
    private List<String> dependsOnOperationIds;
    private Boolean allowDescendantFirstSplit;
    private UUID expectedSourceParentId;
}