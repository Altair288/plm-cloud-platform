package com.plm.common.api.dto;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class MetaAttributeDefDetailDto {
    private String key;
    private String categoryCode;
    private String status;
    private OffsetDateTime createdAt;
    private LatestVersion latestVersion;
    private String lovKey;
    private Boolean hasLov;
    private List<MetaAttributeVersionSummaryDto> versions;

    @Data
    public static class LatestVersion {
        private Integer versionNo;
        private String displayName;
        private String dataType;
        private String unit;
        private String lovKey;
    }
}
