package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryBatchTransferRequestDto {
    private String businessDomain;
    private String action;
    private UUID targetParentId;
    private Boolean dryRun;
    private Boolean atomic;
    private String operator;
    private MetaCategoryBatchCopyOptionsDto copyOptions;
    private List<MetaCategoryBatchTransferOperationDto> operations;
}