package com.plm.common.api.dto;

import lombok.Data;

@Data
public class MetaCategoryBatchCopyOptionsDto {
    private String versionPolicy;
    private String codePolicy;
    private String namePolicy;
    private String defaultStatus;
}