package com.plm.common.api.dto.taxonomy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetaTaxonomyLevelConfigDto {
    private Integer level;
    private String displayName;
}
