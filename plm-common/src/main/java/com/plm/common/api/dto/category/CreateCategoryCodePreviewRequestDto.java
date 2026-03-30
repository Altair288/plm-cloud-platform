package com.plm.common.api.dto.category;

import lombok.Data;

import java.util.UUID;

@Data
public class CreateCategoryCodePreviewRequestDto {
    private String businessDomain;
    private UUID parentId;
    private String manualCode;
    private Integer count;
}