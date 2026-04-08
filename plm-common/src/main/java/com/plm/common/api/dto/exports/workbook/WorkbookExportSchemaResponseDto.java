package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.util.List;

@Data
public class WorkbookExportSchemaResponseDto {
    private String schemaVersion;
    private List<ModuleSchemaDto> modules;

    @Data
    public static class ModuleSchemaDto {
        private String moduleKey;
        private String defaultSheetName;
        private List<FieldSchemaDto> fields;
    }

    @Data
    public static class FieldSchemaDto {
        private String fieldKey;
        private String defaultHeader;
        private String description;
        private String valueType;
        private Boolean defaultSelected;
        private Boolean allowCustomHeader;
    }
}