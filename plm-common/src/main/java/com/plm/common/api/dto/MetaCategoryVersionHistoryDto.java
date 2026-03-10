package com.plm.common.api.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class MetaCategoryVersionHistoryDto {
    private Integer versionNo;
    private OffsetDateTime versionDate;
    private String name;
    private String description;
    private String updatedBy;
    private Boolean latest;
}
