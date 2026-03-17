package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class MetaCategoryBatchTransferResponseDto {
    private Integer total;
    private Integer successCount;
    private Integer failureCount;
    private Integer normalizedCount;
    private Integer movedCount;
    private Integer copiedCount;
    private Boolean atomic;
    private Boolean dryRun;
    private List<String> warnings;
    private List<MetaCategoryBatchTransferItemResultDto> results;
}