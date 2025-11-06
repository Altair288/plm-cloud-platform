package com.plm.common.api.dto;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class MetaAttributeVersionSummaryDto {
    private Integer versionNo;
    private String hash;
    private Boolean latest;
    private OffsetDateTime createdAt;

    public MetaAttributeVersionSummaryDto() {
    }

    public MetaAttributeVersionSummaryDto(Integer versionNo, String hash, Boolean latest, OffsetDateTime createdAt) {
        this.versionNo = versionNo;
        this.hash = hash;
        this.latest = latest;
        this.createdAt = createdAt;
    }
}
