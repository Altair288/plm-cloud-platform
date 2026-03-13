package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryBatchDeleteRequestDto {
    private List<UUID> ids;
    private Boolean cascade;
    private Boolean confirm;
    private String operator;
    private Boolean atomic;
    private Boolean dryRun;
}
