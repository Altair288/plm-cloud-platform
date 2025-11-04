package com.plm.common.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class AttributeImportSummaryDto {
    private int totalRows;
    private int attributeGroupCount; // distinct (category, attribute)
    private int createdAttributeDefs;
    private int createdAttributeVersions;
    private int createdLovDefs;
    private int createdLovVersions;
    private int skippedUnchanged;
    private int errorCount;
    private List<AttributeImportErrorDto> errors;
}
