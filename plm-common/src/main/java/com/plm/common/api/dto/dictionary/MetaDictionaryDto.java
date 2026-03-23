package com.plm.common.api.dto.dictionary;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetaDictionaryDto {
    private String code;
    private String name;
    private Integer version;
    private String source;
    private String locale;
    private List<MetaDictionaryEntryDto> entries = new ArrayList<>();
}
