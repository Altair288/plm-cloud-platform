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
    private String dataType;
    private String unit;
    private Boolean hasLov;
    private Boolean required;
    private Boolean unique;
    private Boolean hidden;
    private Boolean readOnly;
    private Boolean searchable;
    private OffsetDateTime createdAt;

    public MetaAttributeDefListItemDto() {
    }

    public MetaAttributeDefListItemDto(String key, String lovKey, String categoryCode, String status,
            Integer latestVersionNo, String displayName, String dataType, String unit, Boolean hasLov, OffsetDateTime createdAt) {
        this.key = key;
        this.lovKey = lovKey;
        this.categoryCode = categoryCode;
        this.status = status;
        this.latestVersionNo = latestVersionNo;
        this.displayName = displayName;
        this.dataType = dataType;
        this.unit = unit;
        this.hasLov = hasLov;
        this.createdAt = createdAt;
    }

    public MetaAttributeDefListItemDto(String key, String lovKey, String categoryCode, String status,
            Integer latestVersionNo, String displayName, String dataType, String unit, Boolean hasLov,
            Boolean required, Boolean unique, Boolean hidden, Boolean readOnly, Boolean searchable,
            OffsetDateTime createdAt) {
        this.key = key;
        this.lovKey = lovKey;
        this.categoryCode = categoryCode;
        this.status = status;
        this.latestVersionNo = latestVersionNo;
        this.displayName = displayName;
        this.dataType = dataType;
        this.unit = unit;
        this.hasLov = hasLov;
        this.required = required;
        this.unique = unique;
        this.hidden = hidden;
        this.readOnly = readOnly;
        this.searchable = searchable;
        this.createdAt = createdAt;
    }
}
