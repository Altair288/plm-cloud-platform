package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryChildrenBatchRequestDto {
    private String taxonomy;
    private List<UUID> parentIds;
    private String status;
}
