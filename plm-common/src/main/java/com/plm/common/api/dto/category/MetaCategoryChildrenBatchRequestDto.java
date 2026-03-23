package com.plm.common.api.dto.category;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetaCategoryChildrenBatchRequestDto {
    private String businessDomain;
    private List<UUID> parentIds;
    private String status;
}
