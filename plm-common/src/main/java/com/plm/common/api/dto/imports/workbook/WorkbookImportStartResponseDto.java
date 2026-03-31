package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class WorkbookImportStartResponseDto {
    private String jobId;
    private String importSessionId;
    private String status;
    private Boolean atomic;
    private OffsetDateTime createdAt;
}