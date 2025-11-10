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
    // 当 includeValues=true 时返回最新 LOV 的值列表（简单结构）
    private List<LovValueItem> lovValues;

    @Data
    public static class LatestVersion {
        private Integer versionNo;
        private String displayName;
        private String dataType;
        private String unit;
        private String lovKey;
    }

    @Data
    public static class LovValueItem {
        private String code; // 枚举值编码（如果有）
        private String value; // 展示值
        private Integer sort; // 排序
        private Boolean disabled; // 是否禁用
    }
}
