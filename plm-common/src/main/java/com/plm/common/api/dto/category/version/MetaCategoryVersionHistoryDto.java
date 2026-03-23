package com.plm.common.api.dto.category.version;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class MetaCategoryVersionHistoryDto {
    private UUID versionId;
    private Integer versionNo;
    private OffsetDateTime versionDate;
    private String name;
    private String description;
    private String updatedBy;
    private Boolean latest;
}
