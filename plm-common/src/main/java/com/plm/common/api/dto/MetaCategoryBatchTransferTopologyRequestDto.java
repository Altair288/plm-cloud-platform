package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class MetaCategoryBatchTransferTopologyRequestDto {
    private String businessDomain;
    private String action;
    private Boolean dryRun;
    private Boolean atomic;
    private String operator;
    private String planningMode;
    private String orderingStrategy;
    private Boolean strictDependencyValidation;
    private List<MetaCategoryBatchTransferTopologyOperationDto> operations;
}