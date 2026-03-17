package com.plm.common.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class MetaCategorySubtreeTreeNodeDto {
    private UUID id;
    private UUID parentId;
    private String businessDomain;
    private String code;
    private String name;
    private String status;
    private Integer level;
    private Integer depth;
    private String path;
    private Boolean hasChildren;
    private Boolean leaf;
    private Integer sort;
    private List<MetaCategorySubtreeTreeNodeDto> children = new ArrayList<>();
}
