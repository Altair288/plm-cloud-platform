package com.plm.common.api.dto.category.batch;

import lombok.Data;

import java.util.List;

@Data
public class MetaCategoryBatchDeleteResponseDto {
    private Integer total;
    private Integer successCount;
    private Integer failureCount;
    private Integer deletedCount;
    private Integer totalWouldDeleteCount;
    private Boolean atomic;
    private Boolean dryRun;
    private List<MetaCategoryBatchDeleteItemResultDto> results;
}
