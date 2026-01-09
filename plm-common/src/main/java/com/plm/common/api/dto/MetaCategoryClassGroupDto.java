package com.plm.common.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetaCategoryClassGroupDto {
    /** 三级分类（Class） */
    private MetaCategoryBrowseNodeDto clazz;

    /** 四级集合（Commodity） */
    private List<MetaCategoryBrowseNodeDto> commodities = new ArrayList<>();
}
