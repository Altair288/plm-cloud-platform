package com.plm.common.api.dto.category.version;

import lombok.Data;

import java.util.UUID;

@Data
public class MetaCategoryVersionCompareDto {
    private UUID categoryId;
    private String categoryCode;
    private String businessDomain;
    private MetaCategoryVersionSnapshotDto baseVersion;
    private MetaCategoryVersionSnapshotDto targetVersion;
    private MetaCategoryVersionCompareDiffDto diff;
}
