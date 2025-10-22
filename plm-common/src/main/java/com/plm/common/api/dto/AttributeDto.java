package com.plm.common.api.dto;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AttributeDto {
    private UUID id;
    private UUID categoryId;
    private String code;
    private String name;
    private String type;
    private String unit;
    private String lovCode;
    private Integer sortOrder;
    private String description;
    private OffsetDateTime createdAt;
}
