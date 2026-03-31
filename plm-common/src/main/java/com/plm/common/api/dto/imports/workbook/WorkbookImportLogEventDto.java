package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class WorkbookImportLogEventDto {
    private String cursor;
    private Long sequence;
    private OffsetDateTime timestamp;
    private String level;
    private String stage;
    private String eventType;
    private String sheetName;
    private Integer rowNumber;
    private String entityType;
    private String entityKey;
    private String action;
    private String code;
    private String message;
    private Map<String, Object> details;
}