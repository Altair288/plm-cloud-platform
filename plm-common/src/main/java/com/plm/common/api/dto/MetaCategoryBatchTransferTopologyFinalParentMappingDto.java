package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryBatchTransferTopologyFinalParentMappingDto {
    private UUID sourceNodeId;
    private UUID finalParentId;
    private List<String> dependsOnResolved;
}