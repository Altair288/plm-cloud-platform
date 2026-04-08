package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.util.List;

@Data
public class WorkbookExportLogPageDto {
    private String jobId;
    private String nextCursor;
    private List<WorkbookExportLogEventDto> items;
}