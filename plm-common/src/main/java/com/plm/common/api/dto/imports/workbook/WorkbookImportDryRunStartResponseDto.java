package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class WorkbookImportDryRunStartResponseDto {
    private String jobId;
    private String status;
    private String currentStage;
    private OffsetDateTime createdAt;
}