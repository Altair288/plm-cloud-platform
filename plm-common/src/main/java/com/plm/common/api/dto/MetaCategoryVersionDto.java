package com.plm.common.api.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class MetaCategoryVersionDto {
    private UUID id;
    private UUID categoryDefId;
    private Integer versionNo;
    private String displayName;
    private Boolean isLatest;
}
