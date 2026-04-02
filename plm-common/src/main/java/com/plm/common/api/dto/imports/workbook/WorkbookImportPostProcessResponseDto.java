package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class WorkbookImportPostProcessResponseDto {
    private String jobId;
    private String action;
    private String status;
    private String executionMode;
    private OffsetDateTime executedAt;
    private Map<String, Object> details;
}