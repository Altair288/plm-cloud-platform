package com.plm.common.api.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class MetaCategoryDetailDto {
    private UUID id;
    private String code;
    private String businessDomain;
    private String status;
    private UUID parentId;
    private UUID rootId;
    private String rootCode;
    private String path;
    private Integer level;
    private Short depth;
    private Integer sort;
    private String createdBy;
    private OffsetDateTime createdAt;
    private MetaCategoryLatestVersionDto latestVersion;
}
