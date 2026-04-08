package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.util.List;

@Data
public class WorkbookExportPlanResponseDto {
    private WorkbookExportStartRequestDto normalizedRequest;
    private EstimateDto estimate;
    private List<String> warnings;

    @Data
    public static class EstimateDto {
        private Integer categoryRows;
        private Integer attributeRows;
        private Integer enumOptionRows;
    }
}