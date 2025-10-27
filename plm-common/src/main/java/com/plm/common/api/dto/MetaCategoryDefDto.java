package com.plm.common.api.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class MetaCategoryDefDto {
    private UUID id;
    private String codeKey;
    private String status;
    private String path;
    private Short depth;
    private Integer sortOrder;
    private String fullPathName;
    private Boolean isLeaf;
}
