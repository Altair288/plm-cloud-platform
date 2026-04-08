package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

@Data
public class WorkbookExportColumnRequestDto {
    private String fieldKey;
    private String headerText;
    private String clientColumnId;
}