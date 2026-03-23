package com.plm.common.api.dto.dictionary;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class MetaDictionaryEntryDto {
    private String key;
    private String value;
    private String label;
    private Integer order;
    private Boolean enabled;
    private Map<String, Object> extra = new LinkedHashMap<>();
}
