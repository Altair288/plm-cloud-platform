package com.plm.common.api.dto.category.batch;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategoryBatchTransferTopologyItemResultDto {
    private String operationId;
    private UUID sourceNodeId;
    private UUID targetParentId;
    private UUID effectiveSourceParentIdBefore;
    private UUID effectiveTargetParentId;
    private Boolean success;
    private String code;
    private String message;
}