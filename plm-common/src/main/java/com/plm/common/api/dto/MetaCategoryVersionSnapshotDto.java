package com.plm.common.api.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class MetaCategoryVersionSnapshotDto {
    private UUID versionId;
    private Integer versionNo;
    private OffsetDateTime versionDate;
    private String name;
    private String description;
    private String updatedBy;
}
