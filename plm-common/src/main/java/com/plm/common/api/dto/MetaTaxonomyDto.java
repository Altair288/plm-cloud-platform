package com.plm.common.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetaTaxonomyDto {
    private String code;
    private String name;
    private String status;
    private List<MetaTaxonomyLevelConfigDto> levelConfigs = new ArrayList<>();
}
