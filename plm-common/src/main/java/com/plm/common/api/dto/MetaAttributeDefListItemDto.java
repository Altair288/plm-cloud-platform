package com.plm.common.api.dto;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class MetaAttributeDefListItemDto {
    private String key;
    private String lovKey;
    private String categoryCode;
    private String status;
    private Integer latestVersionNo;
    private String displayName;
    private String unit;
    private Boolean hasLov;
    private OffsetDateTime createdAt;

    public MetaAttributeDefListItemDto() {
    }

    public MetaAttributeDefListItemDto(String key, String lovKey, String categoryCode, String status,
            Integer latestVersionNo, String displayName, String unit, Boolean hasLov, OffsetDateTime createdAt) {
        this.key = key;
        this.lovKey = lovKey;
        this.categoryCode = categoryCode;
        this.status = status;
        this.latestVersionNo = latestVersionNo;
        this.displayName = displayName;
        this.unit = unit;
        this.hasLov = hasLov;
        this.createdAt = createdAt;
    }
}
