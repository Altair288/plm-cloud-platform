package com.plm.common.api.dto.category;

import lombok.Data;

import java.util.List;

@Data
public class MetaCategorySearchItemDto {
    private MetaCategoryNodeDto node;
    private String path;
    private List<MetaCategoryNodeDto> pathNodes;
}
