package com.plm.common.api.dto.dictionary;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetaDictionaryBatchResponseDto {
    private List<MetaDictionaryDto> items = new ArrayList<>();
}
