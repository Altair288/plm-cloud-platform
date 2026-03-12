package com.plm.common.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class MetaCategoryVersionCompareDiffDto {
    private Boolean sameVersion;
    private Boolean nameChanged;
    private Boolean descriptionChanged;
    private Boolean structureChanged;
    private List<String> structureChangedPaths;
    private List<String> changedFields;
}
