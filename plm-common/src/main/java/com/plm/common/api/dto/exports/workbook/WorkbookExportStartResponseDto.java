package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class WorkbookExportStartResponseDto {
    private String jobId;
    private String status;
    private String currentStage;
    private OffsetDateTime createdAt;
}