package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

@Data
public class WorkbookImportStartRequestDto {
    private String importSessionId;
    private String dryRunJobId;
    private String operator;
    private Boolean atomic;
    private String executionMode;
    private String overwriteMode;
}