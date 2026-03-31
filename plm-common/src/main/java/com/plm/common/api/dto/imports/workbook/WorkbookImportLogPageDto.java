package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.util.List;

@Data
public class WorkbookImportLogPageDto {
    private String jobId;
    private String nextCursor;
    private List<WorkbookImportLogEventDto> items;
}