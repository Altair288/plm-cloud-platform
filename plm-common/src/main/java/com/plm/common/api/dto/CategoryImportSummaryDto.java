package com.plm.common.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class CategoryImportSummaryDto {
    private int totalRows;              // 数据行（不含表头）
    private int createdCount;           // 新增分类数
    private int skippedExistingCount;   // 已存在跳过数
    private int errorCount;             // 错误行数
    private List<String> errors;        // 每行错误描述
    private List<CategoryDto> createdCategories; // 新创建分类列表
}
