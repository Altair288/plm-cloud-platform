package com.plm.common.api.dto.category.batch;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategoryBatchDeleteItemResultDto {
    private UUID id;
    private Boolean success;
    private Integer deletedCount;
    private Integer wouldDeleteCount;
    private String code;
    private String message;
}
