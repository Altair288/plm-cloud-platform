package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

@Data
public class WorkbookImportDryRunOptionsDto {
    private CodingOptions codingOptions = new CodingOptions();
    private DuplicateOptions duplicateOptions = new DuplicateOptions();

    @Data
    public static class CodingOptions {
        private String categoryCodeMode;
        private String attributeCodeMode;
        private String enumOptionCodeMode;
    }

    @Data
    public static class DuplicateOptions {
        private String categoryDuplicatePolicy;
        private String attributeDuplicatePolicy;
        private String enumOptionDuplicatePolicy;
    }
}