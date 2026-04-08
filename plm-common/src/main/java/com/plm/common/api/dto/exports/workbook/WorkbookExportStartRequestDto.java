package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class WorkbookExportStartRequestDto {
    private String businessDomain;
    private ScopeDto scope;
    private OutputDto output;
    private List<ModuleRequestDto> modules;
    private String operator;
    private String clientRequestId;

    @Data
    public static class ScopeDto {
        private List<UUID> categoryIds;
        private Boolean includeChildren;
    }

    @Data
    public static class OutputDto {
        private String format;
        private String fileName;
        private String pathSeparator;
    }

    @Data
    public static class ModuleRequestDto {
        private String moduleKey;
        private Boolean enabled;
        private String sheetName;
        private List<WorkbookExportColumnRequestDto> columns;
    }
}