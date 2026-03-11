package com.plm.common.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetaDictionaryBatchRequestDto {
    private List<String> codes = new ArrayList<>();
    private String lang;
    private Boolean includeDisabled;
}
