package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class WorkbookExportLogEventDto {
    private String cursor;
    private Long sequence;
    private OffsetDateTime timestamp;
    private String level;
    private String stage;
    private String eventType;
    private String moduleKey;
    private String code;
    private String message;
    private Map<String, Object> details;
}