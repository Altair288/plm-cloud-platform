package com.plm.common.api.dto.category.batch;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategoryBatchTransferOperationDto {
    private String clientOperationId;
    private UUID sourceNodeId;
    private UUID targetParentId;
}