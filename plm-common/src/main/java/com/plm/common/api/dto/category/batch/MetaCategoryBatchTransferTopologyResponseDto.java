package com.plm.common.api.dto.category.batch;

import lombok.Data;

import java.util.List;

@Data
public class MetaCategoryBatchTransferTopologyResponseDto {
    private Integer total;
    private Integer successCount;
    private Integer failureCount;
    private Boolean atomic;
    private Boolean dryRun;
    private String planningMode;
    private List<String> resolvedOrder;
    private List<String> planningWarnings;
    private List<MetaCategoryBatchTransferTopologyFinalParentMappingDto> finalParentMappings;
    private List<MetaCategoryBatchTransferTopologyItemResultDto> results;
}