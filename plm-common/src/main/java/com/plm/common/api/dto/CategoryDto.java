package com.plm.common.api.dto;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class CategoryDto {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private OffsetDateTime createdAt;
    // 可选：导入时标记是否新创建
    private Boolean importedNew;
}
