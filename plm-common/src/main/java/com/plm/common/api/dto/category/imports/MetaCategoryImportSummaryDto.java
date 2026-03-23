package com.plm.common.api.dto.category.imports;

import com.plm.common.api.dto.category.MetaCategoryDefDto;
import lombok.Data;
import java.util.List;

@Data
public class MetaCategoryImportSummaryDto {
    private int totalRows;
    private int createdDefCount;
    private int createdVersionCount;
    private int skippedExistingCount;
    private int errorCount;
    private List<String> errors;
    private List<MetaCategoryDefDto> createdDefs;
}
