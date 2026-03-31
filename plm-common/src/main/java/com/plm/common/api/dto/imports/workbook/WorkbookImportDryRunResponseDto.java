package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class WorkbookImportDryRunResponseDto {
    private String importSessionId;
    private TemplateDto template;
    private SummaryDto summary;
    private ChangeSummaryDto changeSummary;
    private WorkbookImportDryRunOptionsDto resolvedImportOptions;
    private PreviewDto preview;
    private List<IssueDto> issues;
    private OffsetDateTime createdAt;

    @Data
    public static class TemplateDto {
        private Boolean recognized;
        private String templateVersion;
        private List<String> sheetNames;
    }

    @Data
    public static class SummaryDto {
        private Integer categoryRowCount;
        private Integer attributeRowCount;
        private Integer enumRowCount;
        private Integer errorCount;
        private Integer warningCount;
        private Boolean canImport;
    }

    @Data
    public static class ChangeCounterDto {
        private Integer create;
        private Integer update;
        private Integer skip;
        private Integer conflict;
    }

    @Data
    public static class ChangeSummaryDto {
        private ChangeCounterDto categories;
        private ChangeCounterDto attributes;
        private ChangeCounterDto enumOptions;
    }

    @Data
    public static class PreviewDto {
        private List<CategoryPreviewItemDto> categories;
        private List<AttributePreviewItemDto> attributes;
        private List<EnumOptionPreviewItemDto> enumOptions;
    }

    @Data
    public static class CategoryPreviewItemDto {
        private String sheetName;
        private Integer rowNumber;
        private String businessDomain;
        private String excelReferenceCode;
        private String categoryCode;
        private String categoryPath;
        private String resolvedFinalCode;
        private String resolvedFinalPath;
        private String codeMode;
        private String categoryName;
        private String parentPath;
        private String parentCode;
        private String resolvedAction;
        private List<IssueDto> issues;
    }

    @Data
    public static class AttributePreviewItemDto {
        private String sheetName;
        private Integer rowNumber;
        private String businessDomain;
        private String categoryCode;
        private String excelReferenceCode;
        private String attributeKey;
        private String resolvedFinalCode;
        private String codeMode;
        private String attributeName;
        private String attributeField;
        private String dataType;
        private String resolvedAction;
        private List<IssueDto> issues;
    }

    @Data
    public static class EnumOptionPreviewItemDto {
        private String sheetName;
        private Integer rowNumber;
        private String categoryCode;
        private String attributeKey;
        private String excelReferenceCode;
        private String optionCode;
        private String resolvedFinalCode;
        private String codeMode;
        private String optionName;
        private String displayLabel;
        private String resolvedAction;
        private List<IssueDto> issues;
    }

    @Data
    public static class IssueDto {
        private String level;
        private String sheetName;
        private Integer rowNumber;
        private String columnName;
        private String errorCode;
        private String message;
        private String rawValue;
        private String expectedRule;
    }
}