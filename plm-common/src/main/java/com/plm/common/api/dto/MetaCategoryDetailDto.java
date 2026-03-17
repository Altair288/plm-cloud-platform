package com.plm.common.api.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryDetailDto {
    private UUID id;
    private String code;
    private String businessDomain;
    private String status;
    private UUID parentId;
    private String parentCode;
    private String parentName;
    private UUID rootId;
    private String rootCode;
    private String rootName;
    private String path;
    private Integer level;
    private Short depth;
    private Integer sort;
    private UUID copiedFromCategoryId;
    private String description;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String modifiedBy;
    private OffsetDateTime modifiedAt;
    private Integer version;
    private MetaCategoryLatestVersionDto latestVersion;
    private List<MetaCategoryVersionHistoryDto> historyVersions;
}
